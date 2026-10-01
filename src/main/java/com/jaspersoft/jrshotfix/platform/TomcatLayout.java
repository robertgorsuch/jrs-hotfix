package com.jaspersoft.jrshotfix.platform;

import java.nio.file.Path;

/**
 * What {@code detectTomcat} finds under a JRS install dir. Invariant: {@code webappDir} is the
 * deployed application ({@code webapps/jasperserver} or {@code webapps/jasperserver-pro}), so
 * {@code webappDir.resolve("WEB-INF/lib")} is always the jar directory hotfixes touch.
 */
public record TomcatLayout(Path installDir, Path tomcatDir, Path webappDir, String webappName) {}
