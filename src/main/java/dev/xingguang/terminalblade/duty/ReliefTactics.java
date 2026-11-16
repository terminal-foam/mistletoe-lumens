package dev.xingguang.terminalblade.duty;

import java.util.List;

/** Pure geometry. All Point values are block coordinates relative to the village anchor. */
public final class ReliefTactics {
    public static final int VILLAGE_RADIUS=24, INTERCEPT_LIMIT=96, HEIGHT=8;
    public static final int COMMANDER=14, UNIT_COUNT=15;
    private static final List<DutyRules.Post> POSTS=DutyRules.reliefPosts();
    private static final List<Point> PATROL=List.of(
            new Point(6,0,0),new Point(0,0,6),new Point(-6,0,0),new Point(0,0,-6));

    public enum Role { PERIMETER, CENTRAL_PATROL, VILLAGER_GUARD, ESCORT, COMMANDER }
    public record Point(int x,int y,int z){
        Point offset(int dx,int dz){return new Point(x+dx,y,z+dz);}
    }
    public record Order(Role role,Point preferredPost,int radius,int contactRadius){}

    public static boolean validSlot(int slot){return slot>=0&&slot<UNIT_COUNT;}
    public static Role roleForSlot(int slot){
        if(!validSlot(slot))throw new IllegalArgumentException("Invalid relief slot: "+slot);
        if(slot<8)return Role.PERIMETER;
        if(slot<10)return Role.CENTRAL_PATROL;
        if(slot<12)return Role.VILLAGER_GUARD;
        if(slot<COMMANDER)return Role.ESCORT;
        return Role.COMMANDER;
    }
    public static int limit(int slot){return slot>=12?INTERCEPT_LIMIT:VILLAGE_RADIUS;}

    public static Order assignPost(int slot,long gameTime,Point crowd,Point intercept,Point leader){
        Role role=roleForSlot(slot);
        Point p;
        int scan;
        switch(role){
            case PERIMETER -> {
                var sector=POSTS.get(slot);
                int d=sector.dx()!=0&&sector.dz()!=0?14:20;
                p=new Point(sector.dx()*d,0,sector.dz()*d);scan=10;
            }
            case CENTRAL_PATROL -> {
                int n=Math.floorMod(Math.floorDiv(gameTime,120)+(slot-8)*2,4);
                p=PATROL.get(n);scan=8;
            }
            case VILLAGER_GUARD -> {
                int sign=slot==10?1:-1;
                p=crowd.offset(sign*3,sign*2);scan=8;
            }
            case ESCORT -> {
                int sign=slot==12?1:-1;
                p=leader!=null?leader.offset(sign*3,-sign*2):intercept.offset(sign*2,0);
                scan=12;
            }
            case COMMANDER -> {p=intercept;scan=12;}
            default -> throw new AssertionError(role);
        }
        return new Order(role,clamp(p,limit(slot)),limit(slot),scan);
    }

    public static Point intercept(Point waveSpawn){
        if(waveSpawn==null)return new Point(0,0,0);
        return clamp(new Point(waveSpawn.x(),0,waveSpawn.z()),INTERCEPT_LIMIT);
    }
    public static Point clamp(Point p,int radius){
        if(DutyRules.inArea(p.x(),p.y(),p.z(),radius))return p;
        var xz=DutyRules.interceptOffset(p.x(),p.z(),radius,0);
        return new Point(xz[0],Math.clamp(p.y(),-HEIGHT,HEIGHT),xz[1]);
    }
    public static int sector(Point p){
        if(p.x()==0&&p.z()==0)return -1;
        return Math.floorMod((int)Math.round(Math.atan2(p.x(),-p.z())/(Math.PI/4)),8);
    }
    public static boolean allowsPost(int slot,Point p){
        return validSlot(slot)&&DutyRules.inArea(p.x(),p.y(),p.z(),limit(slot))
                && (slot>=8||sector(p)==slot);
    }

    /**
     * During normal navigation all nodes must be inside. A displaced unit may
     * walk back from outside, but only along nodes that do not move farther out.
     */
    public static final class RouteFence {
        private final int radius;
        private double previous;
        public RouteFence(Point start,int radius){this.radius=radius;previous=outside(start);}
        private double outside(Point p){
            return Math.max(0,Math.hypot(p.x(),p.z())-radius)+Math.max(0,Math.abs((double)p.y())-HEIGHT);
        }
        public boolean accept(Point next){
            double distance=outside(next);
            if(distance>previous+1e-8)return false;
            previous=distance;
            return true;
        }
        public boolean reachedDefense(){return previous<=1e-8;}
    }

    private ReliefTactics(){}
}
