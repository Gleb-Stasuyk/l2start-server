package org.l2start.platform.api;

import java.nio.file.Path;

public interface ModuleResources {
    Path root();

    /** File inside the module folder; ".." and absolute paths are rejected. */
    Path path(String relative);

    /** Html path for NpcHtmlMessage.setFile: html("95000.htm") = "data/html/modules/&lt;id&gt;/95000.htm". */
    String html(String relative);
}
