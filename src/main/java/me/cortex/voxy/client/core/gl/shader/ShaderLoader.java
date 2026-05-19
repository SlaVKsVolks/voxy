package me.cortex.voxy.client.core.gl.shader;


import net.minecraft.resources.ResourceLocation;

import org.apache.commons.io.IOUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

public class ShaderLoader {
    public static String parse(String id) {
        var src =  "#version 460 core\n";
        src += String.join("\n", ShaderLoadingParser.parseRoot(ResourceLocation.parse(id)));
        return src;
    }


    //Use our own loader

    private static final class ShaderLoadingParser {
        private static final Pattern IMPORT_PATTERN = Pattern.compile("#import <(?<namespace>.*):(?<path>.*)>");
        public static List<String> parseRoot(ResourceLocation id) {
            List<String> out = new ArrayList<>();
            for (var line : toLines(loadShaderAsset(id))) {
                if (line.startsWith("#version")) {
                    continue;
                } else if (line.startsWith("#import")) {
                    var match = IMPORT_PATTERN.matcher(line);
                    if (!match.matches()) throw new IllegalArgumentException("Unknown import: " + line);
                    var iid = ResourceLocation.fromNamespaceAndPath(match.group("namespace"), match.group("path"));
                    out.addAll(parseRoot(iid));
                } else {
                    out.add(line);
                }
            }
            return out;
        }

        private static List<String> toLines(String src) {
            return new BufferedReader(new StringReader(src)).lines().toList();
        }
        private static String loadShaderAsset(ResourceLocation id) {
            String classpathPath = String.format("assets/%s/shaders/%s", id.getNamespace(), id.getPath());
            String modulePath = "/" + classpathPath;
            try (InputStream in = openShaderAsset(classpathPath, modulePath)) {
                if (in == null) {
                    throw new RuntimeException("Shader not found: " + modulePath);
                } else {
                    return IOUtils.toString(in, StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to read shader source for " + modulePath, e);
            }
        }

        private static InputStream openShaderAsset(String classpathPath, String modulePath) {
            var classLoader = ShaderLoadingParser.class.getClassLoader();
            var fromClasspath = classLoader != null ? classLoader.getResourceAsStream(classpathPath) : null;
            if (fromClasspath != null) {
                return fromClasspath;
            }

            var contextLoader = Thread.currentThread().getContextClassLoader();
            if (contextLoader != null && contextLoader != classLoader) {
                var fromContext = contextLoader.getResourceAsStream(classpathPath);
                if (fromContext != null) {
                    return fromContext;
                }
            }

            return ShaderLoadingParser.class.getResourceAsStream(modulePath);
        }
    }
}
