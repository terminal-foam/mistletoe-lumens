package dev.xingguang.terminalblade.duty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Server-thread batch coordinator. Construction and preflight must not insert
 * entities into the world. Remove must be safe on a unit never inserted.
 */
public final class ReliefDispatch<K,U> {
    public enum Result { NO_RAID, ALREADY_DISPATCHED, NO_SAFE_ENTRY, INSERT_REJECTED, ARRIVED }
    public interface Batch<U> {
        List<U> prepare();
        boolean safe(U unit);
        boolean insert(U unit);
        void remove(U unit);
        void commit();
    }

    private final Set<K> inFlight=Collections.newSetFromMap(new IdentityHashMap<>());

    public Result tryDispatch(K key,boolean recorded,Batch<U> batch) {
        if(key==null)return Result.NO_RAID;
        if(recorded||!inFlight.add(key))return Result.ALREADY_DISPATCHED;
        List<U> attempted=new ArrayList<>(15);
        boolean committed=false;
        Throwable originalFailure=null;
        try {
            List<U> units=batch.prepare();
            if(units.size()!=ReliefTactics.UNIT_COUNT || units.stream().anyMatch(Objects::isNull))
                return Result.NO_SAFE_ENTRY;
            Set<U> distinct=Collections.newSetFromMap(new IdentityHashMap<>());
            for(U unit:units)if(!distinct.add(unit)||!batch.safe(unit))return Result.NO_SAFE_ENTRY;
            for(U unit:units) {
                // Register before calling insert. Even a callback that throws after insertion can be rolled back.
                attempted.add(unit);
                if(!batch.insert(unit))return Result.INSERT_REJECTED;
            }
            batch.commit();
            committed=true;
            return Result.ARRIVED;
        } catch(RuntimeException|Error failure){
            originalFailure=failure;
            throw failure;
        } finally {
            try {
                if(!committed) {
                    Throwable rollbackFailure=null;
                    for(int i=attempted.size()-1;i>=0;i--){
                        try{batch.remove(attempted.get(i));}
                        catch(RuntimeException|Error problem){
                            if(rollbackFailure==null)rollbackFailure=problem;
                            else if(problem!=rollbackFailure)rollbackFailure.addSuppressed(problem);
                        }
                    }
                    if(rollbackFailure!=null){
                        if(originalFailure!=null){
                            if(originalFailure!=rollbackFailure)originalFailure.addSuppressed(rollbackFailure);
                        }else if(rollbackFailure instanceof RuntimeException e)throw e;
                        else throw (Error)rollbackFailure;
                    }
                }
            } finally {
                inFlight.remove(key);
            }
        }
    }
}
