package com.playcity.bridge;

import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

final class BukkitNoticeTargetResolver implements Supplier<NoticePluginAdapter.NoticeTarget> {
    private static final String NOTICE_PLUGIN_NAME = "Notice";

    @Override
    public NoticePluginAdapter.NoticeTarget get() {
        Plugin notice = Bukkit.getPluginManager().getPlugin(NOTICE_PLUGIN_NAME);
        if (notice == null || !notice.isEnabled()) {
            return null;
        }
        return new NoticePluginAdapter.NoticeTarget(
            notice,
            notice.getPluginMeta().getVersion()
        );
    }
}
