package dev.ryanhcode.sable.gradle

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.junit.jupiter.api.Test

import java.nio.charset.StandardCharsets
import java.util.jar.JarEntry
import java.util.jar.JarInputStream
import java.util.jar.JarOutputStream

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

class VeilImGuiCompatibilityPatcherTest {
    private static final String COMPAT_CLASS = 'foundry/veil/impl/client/imgui/VeilImGuiCompat.class'
    private static final String MODS_TOML = 'META-INF/neoforge.mods.toml'
    private static final String LEGACY_PRE_RENDER = 'preRenderImGuiEvent' + 's'
    private static final String LEGACY_POST_RENDER = 'postRenderImGuiEvent' + 's'

    @Test
    void patchesImGuiMcCallsAndDependencyRange() {
        byte[] patchedJar = VeilImGuiCompatibilityPatcher.patchVeilJar(createVeilJar())
        Map<String, byte[]> entries = readEntries(patchedJar)
        List<String> calls = methodCalls(entries[COMPAT_CLASS])
        String classPool = new String(entries[COMPAT_CLASS], StandardCharsets.ISO_8859_1)
        String metadata = new String(entries[MODS_TOML], StandardCharsets.UTF_8)

        assertFalse(calls.contains(LEGACY_PRE_RENDER))
        assertFalse(calls.contains(LEGACY_POST_RENDER))
        assertFalse(classPool.contains(LEGACY_PRE_RENDER))
        assertFalse(classPool.contains(LEGACY_POST_RENDER))
        assertTrue(calls.contains('preRenderImGuiEvent'))
        assertTrue(calls.contains('postRenderImGuiEvent'))
        assertTrue(metadata.contains('versionRange = "[2.0.0,3.0.0)"'))
        assertTrue(metadata.contains('reason = "Veil supports ImGuiMC 2.x"'))
        assertFalse(metadata.contains('versionRange = "[1.1.0,2.0.0)"'))
    }

    private static byte[] createVeilJar() {
        ByteArrayOutputStream output = new ByteArrayOutputStream()
        new JarOutputStream(output).withCloseable { jar ->
            jar.putNextEntry(new JarEntry(COMPAT_CLASS))
            jar.write(createCompatClass())
            jar.closeEntry()
            jar.putNextEntry(new JarEntry(MODS_TOML))
            jar.write('''[[dependencies.veil]]
    modId = "imguimc"
    type = "optional"
    versionRange = "[1.1.0,2.0.0)"
    reason = "Veil supports imguimc 1.1.0 to 2.0.0"
'''.getBytes(StandardCharsets.UTF_8))
            jar.closeEntry()
        }
        output.toByteArray()
    }

    private static byte[] createCompatClass() {
        ClassWriter writer = new ClassWriter(0)
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, 'foundry/veil/impl/client/imgui/VeilImGuiCompat', null, 'java/lang/Object', null)
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, 'load', '()V', null, null)
        method.visitCode()
        method.visitInsn(Opcodes.ACONST_NULL)
        method.visitInsn(Opcodes.ACONST_NULL)
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, 'foundry/imgui/api/ImGuiMCEvents', LEGACY_PRE_RENDER, '(Lfoundry/imgui/api/event/RenderImGuiEvents$Pre;)V', true)
        method.visitInsn(Opcodes.ACONST_NULL)
        method.visitInsn(Opcodes.ACONST_NULL)
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, 'foundry/imgui/api/ImGuiMCEvents', LEGACY_POST_RENDER, '(Lfoundry/imgui/api/event/RenderImGuiEvents$Post;)V', true)
        method.visitInsn(Opcodes.RETURN)
        method.visitMaxs(2, 0)
        method.visitEnd()
        writer.visitEnd()
        writer.toByteArray()
    }

    private static Map<String, byte[]> readEntries(byte[] jarBytes) {
        Map<String, byte[]> entries = [:]
        new JarInputStream(new ByteArrayInputStream(jarBytes)).withCloseable { jar ->
            JarEntry entry
            while ((entry = jar.nextJarEntry) != null) {
                entries[entry.name] = jar.readAllBytes()
            }
        }
        entries
    }

    private static List<String> methodCalls(byte[] classBytes) {
        List<String> calls = []
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    void visitMethodInsn(int opcode, String owner, String methodName, String methodDescriptor, boolean isInterface) {
                        if (owner == 'foundry/imgui/api/ImGuiMCEvents') {
                            calls.add(methodName)
                        }
                    }
                }
            }
        }, 0)
        calls
    }
}
