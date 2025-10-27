package com.catbrain.bot.util;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.jar.Manifest;

@Slf4j
@UtilityClass
public class VersionUtil {
    private static final String DEFAULT_VERSION = "0.2.0-dev";
    private static String cachedVersion;

    public static String getVersion() {
        if (cachedVersion != null) {
            return cachedVersion;
        }

        try {
            var resources = VersionUtil.class.getClassLoader().getResources("META-INF/MANIFEST.MF");
            while (resources.hasMoreElements()) {
                var url = resources.nextElement();
                try (var stream = url.openStream()) {
                    var manifest = new Manifest(stream);
                    var attrs = manifest.getMainAttributes();

                    var implVersion = attrs.getValue("Implementation-Version");
                    if (implVersion != null && !implVersion.isBlank()) {
                        cachedVersion = implVersion;
                        log.debug("Version loaded from manifest: {}", cachedVersion);
                        return cachedVersion;
                    }
                } catch (IOException e) {
                    log.debug("Could not read manifest from {}", url, e);
                }
            }
        } catch (IOException e) {
            log.debug("Could not enumerate manifests", e);
        }

        cachedVersion = DEFAULT_VERSION;
        log.debug("Using default version (not running from JAR): {}", cachedVersion);
        return cachedVersion;
    }

    public static String getFormattedVersion() {
        return "Cat Brain Bot v" + getVersion();
    }
}