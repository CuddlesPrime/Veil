package foundry.veil.forge.mixin.compat.iris;

import com.mojang.blaze3d.platform.GlStateManager;
import foundry.veil.impl.client.render.shader.program.ShaderProgramImpl;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.targets.RenderTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

import static org.lwjgl.opengl.GL11C.GL_TEXTURE;
import static org.lwjgl.opengl.GL11C.glGetInteger;
import static org.lwjgl.opengl.GL30C.*;

@Mixin(ShaderProgramImpl.Wrapper.class)
public class ShaderWrapperMixin {

    // apply and clear are separate methods, so the framebuffer to restore has to be kept on the shader
    @Unique
    private int veil$previousReadFramebuffer = -1;
    @Unique
    private int veil$previousDrawFramebuffer = -1;

    @Inject(method = "apply", at = @At("TAIL"))
    public void apply(CallbackInfo ci) {
        // Redirect into Iris's buffers the same way Iris does for other shaders it doesn't know: only while it's
        // rendering the world (not in GUIs or the shadow pass), and into the copy of colortex0 the pack is using
        Optional<WorldRenderingPipeline> pipelineOptional = Iris.getPipelineManager().getPipeline();
        if (pipelineOptional.isEmpty() || !(pipelineOptional.get() instanceof IrisRenderingPipeline pipeline) || !pipeline.shouldOverrideShaders() || ShadowRenderer.ACTIVE) {
            return;
        }

        int drawFramebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        if (!veil$drawsToScene(pipeline, drawFramebuffer)) {
            return;
        }

        if (this.veil$previousDrawFramebuffer == -1) {
            this.veil$previousReadFramebuffer = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
            this.veil$previousDrawFramebuffer = drawFramebuffer;
        }
        pipeline.bindDefault();
    }

    @Inject(method = "clear", at = @At("TAIL"))
    public void clear(CallbackInfo ci) {
        if (this.veil$previousDrawFramebuffer == -1) {
            return;
        }

        // Restore whatever was bound before, which isn't always the main framebuffer (e.g. Iris's own while in the world)
        GlStateManager._glBindFramebuffer(GL_READ_FRAMEBUFFER, this.veil$previousReadFramebuffer);
        GlStateManager._glBindFramebuffer(GL_DRAW_FRAMEBUFFER, this.veil$previousDrawFramebuffer);
        this.veil$previousReadFramebuffer = -1;
        this.veil$previousDrawFramebuffer = -1;
    }

    /**
     * Whether the bound framebuffer draws into the scene (Minecraft's main framebuffer or Iris's colortex0), rather
     * than into a framebuffer of the caller's own that has to be left alone (e.g. one that's composited later).
     */
    @Unique
    private static boolean veil$drawsToScene(IrisRenderingPipeline pipeline, int drawFramebuffer) {
        if (drawFramebuffer == 0) {
            return true;
        }
        if (glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE) != GL_TEXTURE) {
            return false;
        }

        int texture = glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        if (texture == Minecraft.getInstance().getMainRenderTarget().getColorTextureId()) {
            return true;
        }
        RenderTarget colortex0 = ((IrisRenderingPipelineAccessor) pipeline).getRenderTargets().getOrCreate(0);
        return texture == colortex0.getMainTexture() || texture == colortex0.getAltTexture();
    }
}
