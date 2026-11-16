package dev.xingguang.terminalblade.client;

import dev.xingguang.terminalblade.registry.ModEntities;
import dev.xingguang.terminalblade.registry.ModMenus;
import dev.xingguang.terminalblade.registry.ModParticles;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;

public final class TerminalBladeClient {
    private TerminalBladeClient() {
    }

    public static void registerParticles(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(ModParticles.PETAL.get(), MistletoeParticle.PetalProvider::new);
        event.registerSpriteSet(ModParticles.BERRY.get(), MistletoeParticle.BerryProvider::new);
        event.registerSpriteSet(ModParticles.SPARK.get(), MistletoeParticle.SparkProvider::new);
        event.registerSpriteSet(ModParticles.FROST.get(), MistletoeParticle.FrostProvider::new);
        event.registerSpriteSet(ModParticles.COLD_LIGHT.get(), MistletoeParticle.ColdLightProvider::new);
    }

    public static void registerMenuScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.COMPANION.get(), LumenCompanionScreen::new);
        event.register(ModMenus.LUMEN_SHOP.get(), LumenShopScreen::new);
    }

    public static void registerEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.LUMEN_SEISHIN.get(), LumenGeoRenderer::new);
        event.registerEntityRenderer(ModEntities.LUMEN_COMPANION.get(), LumenCompanionGeoRenderer::new);
    }
}
