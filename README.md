# Afterburner

A performance mod for Minecraft 26.3 (Fabric). It makes chunk rendering, lighting, world generation and server ticks
cheaper, and uses less memory. Download: [Modrinth](https://modrinth.com/mod/afterburner-optimized).

Every optimization can be switched off on its own, in game (Options > Video Settings > Afterburner) or in
`config/afterburner.properties`. When a mod that does the same job is installed (Sodium, Lithium, Starlight, FerriteCore
and others), Afterburner leaves that part to it. The full list, with what each one does, is in
[`Features.java`](src/main/java/com/afterburner/Features.java).

Needs Fabric Loader 0.19.5+, Fabric API and Java 25. Works on a client or a server alone.

## Building

```
./gradlew build
```

with JDK 25. The mod is `build/libs/afterburner-<version>.jar`.

## Layout

- `src/main`: the parts that run on both sides (world generation, lighting, mob AI, collisions, ticks), the settings,
  and the mixin config plugin that leaves out what's switched off.
- `src/client`: rendering (chunk batching, compact vertices, occlusion and entity culling, animations), memory and the
  FPS target.
- `src/bench`: the benchmark and check commands used while developing, as a mod of their own (`afterburner_bench`).
  The dev client loads it; the released jar doesn't contain it.
- `src/labs`: the experimental parts: a shader pack loader, upscaling and far terrain. It's a mod of its own
  (`afterburner_labs`) that the released jar carries inside. The shader pack loader and upscaling turn off with Sodium,
  Iris or Oculus; far terrain turns off with Sodium, Voxy or Distant Horizons.

## Notes

Made with a lot of help from AI (Claude). Claude went through Minecraft's code with me to list what could be made
faster, and wrote the shaders and the code comments. The rendering part (chunk batching, compact vertices, face
culling) was also done with AI.

## Credits

- [Sodium](https://github.com/CaffeineMC/sodium): the rendering optimizations (region batching, the compact vertex
  format, face culling, animating only visible textures) follow Sodium's design, rebuilt on Minecraft's own renderer. No
  Sodium code is used, and Afterburner turns these off when Sodium is installed.
- [Iris](https://github.com/IrisShaders/Iris): the shader pack loader in `src/labs` aims to behave like Iris
  so packs work the same. No Iris code is used, and it turns off when Iris is installed.
- Octahedral normals: Cigolle et al. (2014) and K. Narkowicz (2014). Tangents: E. Lengyel (2001).

## License

MIT, see [LICENSE](LICENSE).
