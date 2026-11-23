package dev.farground.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.farground.BatchedClimate;
import dev.farground.FastBiomes;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

/**
 * Only reached by biome sources that don't have their own {@code createResolverForChunk};
 * see {@link BatchedClimate}.
 */
@Mixin(BiomeSource.class)
public abstract class BiomeSourceMixin {
	@Shadow
	public abstract BiomeResolver createResolver(Climate.Sampler sampler);

	@Inject(method = "createResolverForChunk", at = @At("HEAD"), cancellable = true)
	private void farground$batchClimate(Climate.Sampler sampler, int quartX, int quartY, int quartZ,
			int sizeX, int sizeY, int sizeZ, CallbackInfoReturnable<BiomeResolver> cir) {
		BiomeSource self = (BiomeSource) (Object) this;
		if (BatchedClimate.appliesTo(self)) {
			FastBiomes.used = true;
			cir.setReturnValue(createResolver(BatchedClimate.forChunk(sampler, quartX, quartY, quartZ, sizeX, sizeY, sizeZ)));
		}
	}
}
