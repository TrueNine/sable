package dev.ryanhcode.sable.gradle;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class VeilImGuiCompatibilityPatcher {
    private static final String COMPAT_CLASS = "foundry/veil/impl/client/imgui/VeilImGuiCompat.class";
    private static final String MODS_TOML = "META-INF/neoforge.mods.toml";
    private static final String IMGUI_EVENTS = "foundry/imgui/api/ImGuiMCEvents";
    private static final String LEGACY_PRE_RENDER = "preRenderImGuiEvent" + "s";
    private static final String LEGACY_POST_RENDER = "postRenderImGuiEvent" + "s";
    private static final String OLD_VERSION_RANGE = "\"[1.1.0,2.0.0)\"";
    private static final String NEW_VERSION_RANGE = "\"[2.0.0,3.0.0)\"";
    private static final String OLD_REASON = "Veil supports imguimc 1.1.0 to 2.0.0";
    private static final String NEW_REASON = "Veil supports ImGuiMC 2.x";

    private VeilImGuiCompatibilityPatcher() {
    }

    public static void patchNestedVeilJar(final Path sableJar) throws IOException {
        final AtomicInteger patchedJars = new AtomicInteger();
        final byte[] patched = rewriteZip(Files.readAllBytes(sableJar), (name, bytes) -> {
            if (name.startsWith("META-INF/jarjar/") && name.contains("veil-neoforge-") && name.endsWith(".jar")) {
                patchedJars.incrementAndGet();
                return patchVeilJar(bytes);
            }
            return bytes;
        });
        if (patchedJars.get() != 1) {
            throw new IOException("Expected one nested NeoForge Veil JAR, found " + patchedJars.get());
        }

        final Path temporary = sableJar.resolveSibling(sableJar.getFileName() + ".tmp");
        Files.write(temporary, patched);
        try {
            Files.move(temporary, sableJar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, sableJar, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static byte[] patchVeilJar(final byte[] veilJar) throws IOException {
        final AtomicInteger patchedClasses = new AtomicInteger();
        final AtomicInteger patchedMetadata = new AtomicInteger();
        final byte[] patched = rewriteZip(veilJar, (name, bytes) -> {
            if (COMPAT_CLASS.equals(name)) {
                patchedClasses.incrementAndGet();
                return patchCompatClass(bytes);
            }
            if (MODS_TOML.equals(name)) {
                patchedMetadata.incrementAndGet();
                final String metadata = new String(bytes, StandardCharsets.UTF_8);
                if (!metadata.contains(OLD_VERSION_RANGE) || !metadata.contains(OLD_REASON)) {
                    throw new IOException("Veil does not declare the expected ImGuiMC compatibility metadata");
                }
                return metadata.replace(OLD_VERSION_RANGE, NEW_VERSION_RANGE)
                        .replace(OLD_REASON, NEW_REASON)
                        .getBytes(StandardCharsets.UTF_8);
            }
            return bytes;
        });
        if (patchedClasses.get() != 1 || patchedMetadata.get() != 1) {
            throw new IOException("Veil compatibility targets were not found exactly once");
        }
        return patched;
    }

    private static byte[] patchCompatClass(final byte[] classBytes) throws IOException {
        final ClassReader reader = new ClassReader(classBytes);
        final ClassWriter writer = new ClassWriter(0);
        final AtomicInteger patchedCalls = new AtomicInteger();
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name, final String descriptor, final String signature, final String[] exceptions) {
                final MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM9, visitor) {
                    @Override
                    public void visitMethodInsn(final int opcode, final String owner, final String methodName, final String methodDescriptor, final boolean isInterface) {
                        final String replacement;
                        if (IMGUI_EVENTS.equals(owner) && LEGACY_PRE_RENDER.equals(methodName)) {
                            replacement = "preRenderImGuiEvent";
                        } else if (IMGUI_EVENTS.equals(owner) && LEGACY_POST_RENDER.equals(methodName)) {
                            replacement = "postRenderImGuiEvent";
                        } else {
                            replacement = methodName;
                        }
                        if (!replacement.equals(methodName)) {
                            patchedCalls.incrementAndGet();
                        }
                        super.visitMethodInsn(opcode, owner, replacement, methodDescriptor, isInterface);
                    }
                };
            }
        }, 0);
        if (patchedCalls.get() != 2) {
            throw new IOException("Expected two legacy ImGuiMC calls, found " + patchedCalls.get());
        }
        return writer.toByteArray();
    }

    private static byte[] rewriteZip(final byte[] input, final EntryTransformer transformer) throws IOException {
        final ByteArrayOutputStream output = new ByteArrayOutputStream(input.length);
        try (ZipInputStream zipInput = new ZipInputStream(new ByteArrayInputStream(input));
             ZipOutputStream zipOutput = new ZipOutputStream(output)) {
            ZipEntry entry;
            while ((entry = zipInput.getNextEntry()) != null) {
                final ZipEntry outputEntry = new ZipEntry(entry.getName());
                outputEntry.setTime(entry.getTime());
                zipOutput.putNextEntry(outputEntry);
                if (!entry.isDirectory()) {
                    zipOutput.write(transformer.transform(entry.getName(), zipInput.readAllBytes()));
                }
                zipOutput.closeEntry();
            }
        }
        return output.toByteArray();
    }

    @FunctionalInterface
    private interface EntryTransformer {
        byte[] transform(String name, byte[] bytes) throws IOException;
    }
}
