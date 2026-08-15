package qouteall.imm_ptl.core.compat.sodium_compatibility;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkStatus;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import net.caffeinemc.mods.sodium.client.render.texture.SpriteUtil;
import net.caffeinemc.mods.sodium.client.world.LevelRendererExtension;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.compat.mixin.sodium.IESodiumWorldRenderer;
import qouteall.imm_ptl.core.render.FrustumCuller;

//@OnlyIn(Dist.CLIENT)
public class SodiumInterface {
    
    @Nullable
    public static FrustumCuller frustumCuller = null;
    
    public static class Invoker {
        public boolean isSodiumPresent() {
            return false;
        }
        
        public Object createNewContext(int renderDistance, org.joml.Vector3d initialCameraPos) {
            return null;
        }
        
        public void switchContextWithCurrentWorldRenderer(Object context) {
        
        }

        public void restoreContextWithCurrentWorldRenderer(Object context) {

        }
        
        public void markSpriteActive(TextureAtlasSprite sprite) {
        
        }
        
        public void onClientChunkLoaded(ClientLevel world, int chunkX, int chunkZ) {
        
        }
        
        public void onClientChunkUnloaded(ClientLevel world, int chunkX, int chunkZ) {
        
        }
    }
    
    public static Invoker invoker = new Invoker();
    
    public static class OnSodiumPresent extends Invoker {
        @Override
        public boolean isSodiumPresent() {
            return true;
        }
        
        @Override
        public Object createNewContext(int renderDistance, org.joml.Vector3d initialCameraPos) {
            return new SodiumRenderingContext(renderDistance, initialCameraPos);
        }
        
        @Override
        public void switchContextWithCurrentWorldRenderer(Object context) {
            SodiumWorldRenderer swr =
                ((LevelRendererExtension) Minecraft.getInstance().levelRenderer).sodium$getWorldRenderer();

            RenderSectionManager renderSectionManager =
                ((IESodiumWorldRenderer) swr).ip_getRenderSectionManager();
            
            ((IESodiumRenderSectionManager) renderSectionManager)
                .ip_swapContext(((SodiumRenderingContext) context));
            
            // Keep movement detection tied to the camera for the active Sodium context.
            var tmp = ((IESodiumWorldRenderer) swr).ip_getLastCameraPos();
            ((IESodiumWorldRenderer) swr).ip_setLastCameraPos(((SodiumRenderingContext) context).lastCameraPos);
            ((SodiumRenderingContext) context).lastCameraPos = tmp;
            
            swr.scheduleTerrainUpdate();
        }

        @Override
        public void restoreContextWithCurrentWorldRenderer(Object context) {
            SodiumWorldRenderer swr =
                ((LevelRendererExtension) Minecraft.getInstance().levelRenderer).sodium$getWorldRenderer();

            RenderSectionManager renderSectionManager =
                ((IESodiumWorldRenderer) swr).ip_getRenderSectionManager();

            ((IESodiumRenderSectionManager) renderSectionManager)
                .ip_swapContext(((SodiumRenderingContext) context));

            var tmp = ((IESodiumWorldRenderer) swr).ip_getLastCameraPos();
            ((IESodiumWorldRenderer) swr).ip_setLastCameraPos(((SodiumRenderingContext) context).lastCameraPos);
            ((SodiumRenderingContext) context).lastCameraPos = tmp;
        }
        
        @Override
        public void markSpriteActive(TextureAtlasSprite sprite) {
            SpriteUtil.markSpriteActive(sprite);
        }
        
        @Override
        public void onClientChunkLoaded(ClientLevel world, int chunkX, int chunkZ) {
            ChunkTrackerHolder.get(world)
                .onChunkStatusAdded(chunkX, chunkZ, ChunkStatus.FLAG_HAS_BLOCK_DATA);
        }
        
        @Override
        public void onClientChunkUnloaded(ClientLevel world, int chunkX, int chunkZ) {
            ChunkTrackerHolder.get(world)
                .onChunkStatusRemoved(chunkX, chunkZ, ChunkStatus.FLAG_HAS_BLOCK_DATA);
        }
    }
    
}
