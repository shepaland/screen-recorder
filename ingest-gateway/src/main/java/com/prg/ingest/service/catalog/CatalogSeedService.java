package com.prg.ingest.service.catalog;

import com.prg.ingest.entity.catalog.AppAlias;
import com.prg.ingest.entity.catalog.AppAlias.AliasType;
import com.prg.ingest.entity.catalog.AppGroup;
import com.prg.ingest.entity.catalog.AppGroup.GroupType;
import com.prg.ingest.entity.catalog.AppGroupItem;
import com.prg.ingest.entity.catalog.AppGroupItem.ItemType;
import com.prg.ingest.entity.catalog.AppGroupItem.MatchType;
import com.prg.ingest.repository.catalog.AppAliasRepository;
import com.prg.ingest.repository.catalog.AppGroupItemRepository;
import com.prg.ingest.repository.catalog.AppGroupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class CatalogSeedService {

    private final AppGroupRepository groupRepo;
    private final AppGroupItemRepository itemRepo;
    private final AppAliasRepository aliasRepo;

    /**
     * Seed default groups, items, and aliases for a tenant.
     * If force=true, deletes existing data first.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void seed(UUID tenantId, GroupType groupType, boolean force) {
        log.info("Seeding catalog for tenant={} groupType={} force={}", tenantId, groupType, force);

        if (force) {
            List<AppGroup> existing = groupRepo.findByTenantIdAndGroupTypeOrderBySortOrder(tenantId, groupType);
            groupRepo.deleteAll(existing);
        }

        long existingCount = groupRepo.countByTenantIdAndGroupType(tenantId, groupType);
        if (existingCount > 0) {
            log.debug("Groups already exist for tenant={} groupType={}, skipping seed", tenantId, groupType);
            return;
        }

        if (groupType == GroupType.APP) {
            seedAppGroups(tenantId);
            seedAppAliases(tenantId);
        } else {
            seedSiteGroups(tenantId);
            seedSiteAliases(tenantId);
        }
    }

    private void seedAppGroups(UUID tenantId) {
        createGroupWithItems(tenantId, GroupType.APP, "Мессенджеры", "Приложения для обмена сообщениями", "#4CAF50", 1,
                List.of("telegram.exe", "slack.exe", "teams.exe", "skype.exe", "viber.exe",
                        "whatsapp.exe", "discord.exe", "zoom.exe"));

        createGroupWithItems(tenantId, GroupType.APP, "Офисные приложения", "Офисный пакет и документы", "#2196F3", 2,
                List.of("winword.exe", "excel.exe", "powerpnt.exe", "outlook.exe", "onenote.exe",
                        "msaccess.exe", "mspub.exe", "libreoffice.exe", "soffice.exe"));

        createGroupWithItems(tenantId, GroupType.APP, "Браузеры", "Веб-браузеры", "#FF9800", 3,
                List.of("chrome.exe", "msedge.exe", "firefox.exe", "opera.exe", "brave.exe",
                        "yandex.exe", "iexplore.exe", "safari"));

        createGroupWithItems(tenantId, GroupType.APP, "Разработка", "Инструменты разработки", "#9C27B0", 4,
                List.of("code.exe", "idea64.exe", "pycharm64.exe", "webstorm64.exe", "rider64.exe",
                        "devenv.exe", "studio64.exe", "postman.exe", "dbeaver.exe",
                        "datagrip64.exe", "gitkraken.exe", "sourcetree.exe"));

        createGroupWithItems(tenantId, GroupType.APP, "Системные", "Системные утилиты и инструменты", "#607D8B", 5,
                List.of("explorer.exe", "cmd.exe", "powershell.exe", "taskmgr.exe", "regedit.exe",
                        "mmc.exe", "control.exe", "mstsc.exe", "terminal.exe",
                        "windowsterminal.exe", "wt.exe"));

        createGroupWithItems(tenantId, GroupType.APP, "Мультимедиа", "Медиаплееры и редакторы", "#E91E63", 6,
                List.of("vlc.exe", "wmplayer.exe", "spotify.exe", "audacity.exe",
                        "photoshop.exe", "gimp.exe", "figma.exe", "obs64.exe"));

        createGroupWithItems(tenantId, GroupType.APP, "CRM/Телефония", "CRM системы и программная телефония", "#00BCD4", 7,
                List.of("bitrix24.exe", "amocrm.exe", "zoiper.exe", "xlite.exe",
                        "microsip.exe", "3cxphone.exe", "sipphone.exe"));

        createGroupWithItems(tenantId, GroupType.APP, "Прочее", "Неклассифицированные приложения", "#9E9E9E", 100, true,
                List.of());
    }

    private void seedSiteGroups(UUID tenantId) {
        createGroupWithItems(tenantId, GroupType.SITE, "Почта", "Почтовые сервисы", "#F44336", 1,
                List.of("mail.google.com", "outlook.live.com", "outlook.office.com", "mail.yandex.ru",
                        "mail.ru", "e.mail.ru", "mail.yahoo.com"));

        createGroupWithItems(tenantId, GroupType.SITE, "Поиск", "Поисковые системы", "#2196F3", 2,
                List.of("google.com", "yandex.ru", "bing.com", "duckduckgo.com", "ya.ru"));

        createGroupWithItems(tenantId, GroupType.SITE, "Мессенджеры", "Веб-версии мессенджеров", "#4CAF50", 3,
                List.of("web.telegram.org", "web.whatsapp.com", "teams.microsoft.com",
                        "slack.com", "discord.com", "app.zoom.us"));

        createGroupWithItems(tenantId, GroupType.SITE, "Соцсети", "Социальные сети", "#E91E63", 4,
                List.of("vk.com", "facebook.com", "instagram.com", "twitter.com", "x.com",
                        "linkedin.com", "ok.ru", "tiktok.com"));

        createGroupWithItems(tenantId, GroupType.SITE, "Облачные хранилища", "Облачные файловые хранилища", "#FF9800", 5,
                List.of("drive.google.com", "onedrive.live.com", "dropbox.com",
                        "disk.yandex.ru", "cloud.mail.ru"));

        createGroupWithItems(tenantId, GroupType.SITE, "Разработка", "Ресурсы для разработчиков", "#9C27B0", 6,
                List.of("github.com", "gitlab.com", "bitbucket.org", "stackoverflow.com",
                        "npmjs.com", "hub.docker.com", "jira.atlassian.com"));

        createGroupWithItems(tenantId, GroupType.SITE, "Видео/Развлечения", "Видеохостинги и развлечения", "#FF5722", 7,
                List.of("youtube.com", "twitch.tv", "netflix.com", "kinopoisk.ru",
                        "ivi.ru", "rutube.ru"));

        createGroupWithItems(tenantId, GroupType.SITE, "Новости", "Новостные порталы", "#795548", 8,
                List.of("news.yandex.ru", "rbc.ru", "lenta.ru", "ria.ru",
                        "kommersant.ru", "habr.com"));

        createGroupWithItems(tenantId, GroupType.SITE, "CRM/Бизнес", "CRM и бизнес-приложения", "#00BCD4", 9,
                List.of("bitrix24.ru", "amocrm.ru", "salesforce.com",
                        "notion.so", "trello.com", "asana.com", "monday.com"));

        createGroupWithItems(tenantId, GroupType.SITE, "Прочее", "Неклассифицированные сайты", "#9E9E9E", 100, true,
                List.of());
    }

    private void seedAppAliases(UUID tenantId) {
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put("chrome.exe", "Google Chrome");
        aliases.put("msedge.exe", "Microsoft Edge");
        aliases.put("firefox.exe", "Mozilla Firefox");
        aliases.put("opera.exe", "Opera");
        aliases.put("brave.exe", "Brave");
        aliases.put("yandex.exe", "Yandex Browser");
        aliases.put("iexplore.exe", "Internet Explorer");
        aliases.put("safari", "Safari");
        aliases.put("telegram.exe", "Telegram");
        aliases.put("slack.exe", "Slack");
        aliases.put("teams.exe", "Microsoft Teams");
        aliases.put("skype.exe", "Skype");
        aliases.put("viber.exe", "Viber");
        aliases.put("whatsapp.exe", "WhatsApp");
        aliases.put("discord.exe", "Discord");
        aliases.put("zoom.exe", "Zoom");
        aliases.put("winword.exe", "Microsoft Word");
        aliases.put("excel.exe", "Microsoft Excel");
        aliases.put("powerpnt.exe", "Microsoft PowerPoint");
        aliases.put("outlook.exe", "Microsoft Outlook");
        aliases.put("onenote.exe", "Microsoft OneNote");
        aliases.put("code.exe", "Visual Studio Code");
        aliases.put("idea64.exe", "IntelliJ IDEA");
        aliases.put("pycharm64.exe", "PyCharm");
        aliases.put("webstorm64.exe", "WebStorm");
        aliases.put("rider64.exe", "JetBrains Rider");
        aliases.put("devenv.exe", "Visual Studio");
        aliases.put("postman.exe", "Postman");
        aliases.put("dbeaver.exe", "DBeaver");
        aliases.put("datagrip64.exe", "DataGrip");
        aliases.put("explorer.exe", "Проводник Windows");
        aliases.put("cmd.exe", "Командная строка");
        aliases.put("powershell.exe", "PowerShell");
        aliases.put("taskmgr.exe", "Диспетчер задач");
        aliases.put("mstsc.exe", "Удаленный рабочий стол");
        aliases.put("vlc.exe", "VLC Media Player");
        aliases.put("spotify.exe", "Spotify");
        aliases.put("figma.exe", "Figma");
        aliases.put("obs64.exe", "OBS Studio");
        aliases.put("notepad.exe", "Блокнот");
        aliases.put("notepad++.exe", "Notepad++");
        aliases.put("calc.exe", "Калькулятор");

        for (var entry : aliases.entrySet()) {
            createAliasIfNotExists(tenantId, AliasType.APP, entry.getKey(), entry.getValue());
        }
    }

    private void seedSiteAliases(UUID tenantId) {
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put("google.com", "Google");
        aliases.put("youtube.com", "YouTube");
        aliases.put("mail.google.com", "Gmail");
        aliases.put("drive.google.com", "Google Drive");
        aliases.put("docs.google.com", "Google Docs");
        aliases.put("yandex.ru", "Яндекс");
        aliases.put("mail.yandex.ru", "Яндекс.Почта");
        aliases.put("disk.yandex.ru", "Яндекс.Диск");
        aliases.put("mail.ru", "Mail.ru");
        aliases.put("vk.com", "ВКонтакте");
        aliases.put("ok.ru", "Одноклассники");
        aliases.put("github.com", "GitHub");
        aliases.put("stackoverflow.com", "Stack Overflow");
        aliases.put("habr.com", "Хабр");
        aliases.put("linkedin.com", "LinkedIn");

        for (var entry : aliases.entrySet()) {
            createAliasIfNotExists(tenantId, AliasType.SITE, entry.getKey(), entry.getValue());
        }
    }

    private void createGroupWithItems(UUID tenantId, GroupType groupType, String name,
                                       String description, String color, int sortOrder,
                                       List<String> patterns) {
        createGroupWithItems(tenantId, groupType, name, description, color, sortOrder, false, patterns);
    }

    private void createGroupWithItems(UUID tenantId, GroupType groupType, String name,
                                       String description, String color, int sortOrder,
                                       boolean isDefault, List<String> patterns) {
        if (groupRepo.existsByTenantIdAndGroupTypeAndNameIgnoreCase(tenantId, groupType, name)) {
            return;
        }

        AppGroup group;
        try {
            group = AppGroup.builder()
                    .tenantId(tenantId)
                    .groupType(groupType)
                    .name(name)
                    .description(description)
                    .color(color)
                    .sortOrder(sortOrder)
                    .isDefault(isDefault)
                    .build();
            group = groupRepo.saveAndFlush(group);
        } catch (DataIntegrityViolationException e) {
            // Race condition: concurrent request already created this group
            log.debug("Group '{}' already exists for tenant={} (concurrent insert), skipping", name, tenantId);
            return;
        }

        ItemType itemType = groupType == GroupType.APP ? ItemType.APP : ItemType.SITE;
        for (String pattern : patterns) {
            try {
                if (!itemRepo.existsByTenantIdAndItemTypeAndPatternIgnoreCase(tenantId, itemType, pattern)) {
                    AppGroupItem item = AppGroupItem.builder()
                            .tenantId(tenantId)
                            .group(group)
                            .itemType(itemType)
                            .pattern(pattern)
                            .matchType(MatchType.EXACT)
                            .build();
                    itemRepo.saveAndFlush(item);
                }
            } catch (DataIntegrityViolationException e) {
                log.debug("Item '{}' already exists for tenant={} (concurrent insert), skipping", pattern, tenantId);
            }
        }
    }

    private void createAliasIfNotExists(UUID tenantId, AliasType aliasType,
                                         String original, String displayName) {
        if (!aliasRepo.existsByTenantIdAndAliasTypeAndOriginalIgnoreCase(tenantId, aliasType, original)) {
            try {
                AppAlias alias = AppAlias.builder()
                        .tenantId(tenantId)
                        .aliasType(aliasType)
                        .original(original)
                        .displayName(displayName)
                        .build();
                aliasRepo.saveAndFlush(alias);
            } catch (DataIntegrityViolationException e) {
                log.debug("Alias '{}' already exists for tenant={} (concurrent insert), skipping", original, tenantId);
            }
        }
    }
}
