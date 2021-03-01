package org.valkyrienskies.mod.mixin.client.render;

import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.*;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher;
import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Matrix3f;
import net.minecraft.util.math.Matrix4f;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockRenderView;
import org.joml.*;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;
import org.valkyrienskies.core.game.ships.ShipObject;
import org.valkyrienskies.core.game.ships.ShipTransform;
import org.valkyrienskies.mod.common.VSGameUtils;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.util.VectorConversionsMCKt;

/**
 * This mixin allows {@link WorldRenderer} to render ship chunks.
 */
@Mixin(WorldRenderer.class)
public class MixinWorldRenderer {

    @Shadow
    @Final
    private ObjectList<WorldRenderer.ChunkInfo> visibleChunks;
    @Shadow
    private ClientWorld world;
    @Shadow
    private BuiltChunkStorage chunks;
    @Shadow
    @Final
    private MinecraftClient client;
    @Shadow
    @Final
    private BufferBuilderStorage bufferBuilders;

    /**
     * This mixin tells the {@link WorldRenderer} to render ship chunks.
     */
    @Inject(
            method = "setupTerrain",
            at = @At(
                    value = "INVOKE",
                    target = "Lit/unimi/dsi/fastutil/objects/ObjectList;iterator()Lit/unimi/dsi/fastutil/objects/ObjectListIterator;"
            )
    )
    private void addShipVisibleChunks(Camera camera, Frustum frustum, boolean hasForcedFrustum, int frame, boolean spectator, CallbackInfo ci) {
        final WorldRenderer self = WorldRenderer.class.cast(this);
        final BlockPos.Mutable tempPos = new BlockPos.Mutable();
        for (ShipObject shipObject : VSGameUtilsKt.getShipObjectWorld(world).getUuidToShipObjectMap().values()) {
            shipObject.getShipData().getShipActiveChunksSet().iterateChunkPos((x, z) -> {
                for (int y = 0; y < 16; y++) {
                    tempPos.set(x << 4, y << 4, z << 4);
                    final ChunkBuilder.BuiltChunk renderChunk = chunks.getRenderedChunk(tempPos);
                    if (renderChunk != null) {
                        final WorldRenderer.ChunkInfo newChunkInfo = MixinWorldRendererChunkInfo.invoker$new(self, renderChunk, null, 0);
                        visibleChunks.add(newChunkInfo);
                    }
                }
                return null;
            });
        }
    }

    /**
     * @reason This mixin forces the game to always render block damage.
     */
    @ModifyConstant(
            method = "render",
            constant = @Constant(
                    doubleValue = 1024,
                    ordinal = 0
            ))
    private double disableBlockDamageDistanceCheck(double originalBlockDamageDistanceConstant) {
        return Double.MAX_VALUE;
    }

    /**
     * This mixin makes block damage render on ships.
     */
    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/block/BlockRenderManager;renderDamage(Lnet/minecraft/block/BlockState;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/world/BlockRenderView;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumer;)V"))
    private void renderBlockDamage(BlockRenderManager blockRenderManager, BlockState state, BlockPos blockPos, BlockRenderView blockRenderWorld, MatrixStack matrix, VertexConsumer vertexConsumer,
                                   MatrixStack methodMatrices, float methodTickDelta, long methodLimitTime, boolean methodRenderBlockOutline, Camera methodCamera, GameRenderer methodGameRenderer, LightmapTextureManager methodLightmapTextureManager, Matrix4f methodMatrix4f) {
        final ShipObject ship = VSGameUtils.getShipObjectManagingPos(world, blockPos);
        if (ship != null) {
            // Remove the vanilla render transform
            methodMatrices.pop();
            // Add the VS render transform
            methodMatrices.push();

            final ShipTransform renderTransform = ship.getRenderTransform();
            final Matrix4dc shipToWorldMatrix = renderTransform.getShipToWorldMatrix();

            final Matrix4d renderMatrix = new Matrix4d();
            final Vec3d cameraPos = methodCamera.getPos();
            renderMatrix.translate(-cameraPos.getX(), -cameraPos.getY(), -cameraPos.getZ());
            renderMatrix.mul(shipToWorldMatrix);
            renderMatrix.translate(blockPos.getX(), blockPos.getY(), blockPos.getZ());

            multiplyMatrixStack(methodMatrices, renderMatrix, renderTransform.getShipCoordinatesToWorldCoordinatesRotation());

            final OverlayVertexConsumerAccessor vertexConsumerAccessor = (OverlayVertexConsumerAccessor) vertexConsumer;

            final Matrix3f newNormalMatrix = new Matrix3f(); // methodMatrices.peek().getNormal().copy();
            newNormalMatrix.loadIdentity(); // Not correct, but at least it renders
            // newNormalMatrix.invert();

            final Matrix4f newModelMatrix = new Matrix4f(); // methodMatrices.peek().getModel().copy();
            newModelMatrix.loadIdentity(); // Not correct, but at least it renders
            // newModelMatrix.invert();

            vertexConsumerAccessor.setNormalMatrix(newNormalMatrix);
            vertexConsumerAccessor.setTextureMatrix(newModelMatrix);

            blockRenderManager.renderDamage(state, blockPos, blockRenderWorld, matrix, vertexConsumer);
        } else {
            // Vanilla behavior
            blockRenderManager.renderDamage(state, blockPos, blockRenderWorld, matrix, vertexConsumer);
        }
    }

    /**
     * This mixin tells the game where to render chunks; which allows us to render ship chunks in arbitrary positions.
     */
    @Inject(method = "renderLayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gl/VertexBuffer;bind()V"), locals = LocalCapture.CAPTURE_FAILHARD)
    private void renderShipChunk(RenderLayer renderLayer, MatrixStack matrixStack, double playerCameraX, double playerCameraY, double playerCameraZ, CallbackInfo ci,
                                 boolean bl, ObjectListIterator<?> objectListIterator, WorldRenderer.ChunkInfo chunkInfo2, ChunkBuilder.BuiltChunk builtChunk, VertexBuffer vertexBuffer) {
        final BlockPos renderChunkOrigin = builtChunk.getOrigin();
        final ShipObject getShipObjectManagingPos = VSGameUtils.getShipObjectManagingPos(world, renderChunkOrigin);
        if (getShipObjectManagingPos != null) {
            // This render chunk is a ship chunk, give it the ship chunk render transform
            final ShipTransform renderTransform = getShipObjectManagingPos.getRenderTransform();
            final Matrix4dc shipToWorldMatrix = renderTransform.getShipToWorldMatrix();

            // Create the render matrix from the render transform and player position
            final Matrix4d renderMatrix = new Matrix4d();
            renderMatrix.translate(-playerCameraX, -playerCameraY, -playerCameraZ);
            renderMatrix.mul(shipToWorldMatrix);
            renderMatrix.translate(renderChunkOrigin.getX(), renderChunkOrigin.getY(), renderChunkOrigin.getZ());

            // Apply the render matrix to the
            multiplyMatrixStack(matrixStack, renderMatrix, renderTransform.getShipCoordinatesToWorldCoordinatesRotation());
        } else {
            // Restore MC default behavior (that was removed by cancelDefaultTransform())
            matrixStack.translate(renderChunkOrigin.getX() - playerCameraX, renderChunkOrigin.getY() - playerCameraY, renderChunkOrigin.getZ() - playerCameraZ);
        }
    }

    /**
     * This mixin removes the vanilla code that determines where each chunk renders.
     */
    @Redirect(method = "renderLayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/util/math/MatrixStack;translate(DDD)V"))
    private void cancelDefaultTransform(MatrixStack matrixStack, double x, double y, double z) {
        // Do nothing
    }

    /**
     * This mixin makes {@link BlockEntity} in the ship render in the correct place.
     */
    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/block/entity/BlockEntityRenderDispatcher;render(Lnet/minecraft/block/entity/BlockEntity;FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;)V"))
    private void renderShipChunkBlockEntity(BlockEntityRenderDispatcher blockEntityRenderDispatcher, BlockEntity blockEntity, float tickDelta, MatrixStack matrix, VertexConsumerProvider vertexConsumerProvider,
                                            MatrixStack methodMatrices, float methodTickDelta, long methodLimitTime, boolean methodRenderBlockOutline, Camera methodCamera, GameRenderer methodGameRenderer, LightmapTextureManager methodLightmapTextureManager, Matrix4f methodMatrix4f) {
        final BlockPos blockEntityPos = blockEntity.getPos();
        final ShipObject getShipObjectManagingPos = VSGameUtils.getShipObjectManagingPos(world, blockEntityPos);
        if (getShipObjectManagingPos != null) {
            // Remove the vanilla render transform
            matrix.pop();

            // Add the VS render transform
            matrix.push();

            final ShipTransform renderTransform = getShipObjectManagingPos.getRenderTransform();
            final Matrix4dc shipToWorldMatrix = renderTransform.getShipToWorldMatrix();

            // Create the render matrix from the render transform and player position
            final Matrix4d renderMatrix = new Matrix4d();
            final Vec3d cameraPos = methodCamera.getPos();
            renderMatrix.translate(-cameraPos.getX(), -cameraPos.getY(), -cameraPos.getZ());
            renderMatrix.mul(shipToWorldMatrix);
            renderMatrix.translate(blockEntityPos.getX(), blockEntityPos.getY(), blockEntityPos.getZ());

            // Apply the render matrix to the
            multiplyMatrixStack(matrix, renderMatrix, renderTransform.getShipCoordinatesToWorldCoordinatesRotation());
        }
        blockEntityRenderDispatcher.render(blockEntity, tickDelta, matrix, vertexConsumerProvider);
    }

    /**
     * @param matrixStack    The {@link MatrixStack} we are multiplying
     * @param matrix         The {@link Matrix4dc} the matrix we are multiplying the {@link MatrixStack} by.
     * @param matrixRotation The rotation quaternion of the matrix.
     */
    private void multiplyMatrixStack(MatrixStack matrixStack, Matrix4dc matrix, Quaterniondc matrixRotation) {
        VectorConversionsMCKt.multiply(matrixStack, matrix, matrixRotation);
    }
}