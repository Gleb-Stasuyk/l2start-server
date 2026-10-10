package org.l2start.bots.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collection;
import java.util.Locale;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.l2jmobius.commons.util.StringUtil;
import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.config.ServerConfig;
import org.l2jmobius.gameserver.config.custom.FactionSystemConfig;
import org.l2jmobius.gameserver.config.custom.StartingLocationConfig;
import org.l2jmobius.gameserver.config.custom.StartingTitleConfig;
import org.l2jmobius.gameserver.data.sql.CharInfoTable;
import org.l2jmobius.gameserver.data.xml.FakePlayerData;
import org.l2jmobius.gameserver.data.xml.InitialEquipmentData;
import org.l2jmobius.gameserver.data.xml.InitialShortcutData;
import org.l2jmobius.gameserver.data.xml.PlayerTemplateData;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.data.xml.SkillTreeData;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.appearance.PlayerAppearance;
import org.l2jmobius.gameserver.entity.actor.stat.PlayerStat;
import org.l2jmobius.gameserver.entity.actor.templates.PlayerTemplate;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.holders.InitialEquipment;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.mechanics.events.Containers;
import org.l2jmobius.gameserver.mechanics.events.EventDispatcher;
import org.l2jmobius.gameserver.mechanics.events.EventType;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.player.OnPlayerCreate;
import org.l2jmobius.gameserver.mechanics.skill.holders.SkillLearn;
import org.l2jmobius.gameserver.network.Disconnection;
import org.l2start.bots.persona.ClassPicker;
import org.l2start.bots.persona.Persona;
import org.l2start.bots.persona.PersonaGenerator;

/**
 * Creates a bot exactly like the stock character creation (CharacterCreate.initNewChar) creates a player's
 * character, minus what only a client needs (tutorial, character selection screen): same starting location,
 * adena/level/SP from config, starting items (so starter-kit applies to bots too), skills, shortcuts and the
 * ON_PLAYER_CREATE event. Each bot gets its own account, banned in the login server.
 */
public final class BotFactory {
    private static final char[] ACCOUNT_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private final SecureRandom secure = new SecureRandom();
    private final BotRepository repo;
    private final BotRegistry registry;
    private final Supplier<PersonaGenerator> personas;
    private final LongSupplier seeds;

    /** @param personas current generator (replaced on //module reload), @param seeds a new persona seed per bot */
    public BotFactory(BotRepository repo, BotRegistry registry, Supplier<PersonaGenerator> personas, LongSupplier seeds) {
        this.repo = repo;
        this.registry = registry;
        this.personas = personas;
        this.seeds = seeds;
    }

    /** @param classId a starting class, or -1 for the configured distribution */
    public synchronized BotRecord create(int classId) throws Exception {
        if ((classId >= 0) && !ClassPicker.isStartingClass(classId)) {
            throw new IllegalArgumentException("class " + classId + " is not a starting class");
        }
        final String login = newAccountName();
        repo.createAccount(login, unusablePasswordHash());
        Player player = null;
        String name = null;
        Persona persona = null;
        try {
            synchronized (CharInfoTable.getInstance()) {
                persona = personas.get().generate(seeds.getAsLong(), classId, BotFactory::nameTaken);
                name = persona.name();
                final PlayerTemplate template = PlayerTemplateData.getInstance().getTemplate(persona.classId());
                if (template == null) {
                    throw new IllegalStateException("no player template for class " + persona.classId());
                }
                player = Player.create(template, login, name, new PlayerAppearance((byte) persona.face(), (byte) persona.hairColor(), (byte) persona.hairStyle(), persona.female()));
            }
            if (player == null) {
                throw new IllegalStateException("Player.create returned null for " + name);
            }
            player.setCurrentHp(player.getMaxHp());
            player.setCurrentMp(player.getMaxMp());
            initNewChar(player);
            final int botId = repo.insertIdentity(player.getObjectId(), login, name, persona.classId());
            repo.savePersona(botId, persona);
            repo.ensureState(botId, player.getX(), player.getY(), player.getZ()); // home = where it was created
            final BotRecord r = new BotRecord(botId, player.getObjectId(), login, name, persona.classId(), BotRecord.Status.ACTIVE, 0, 0);
            r.setPersona(persona);
            r.setHome(player.getX(), player.getY(), player.getZ());
            registry.add(r);
            return r;
        } catch (Exception e) {
            if (player == null) {
                repo.dropAccount(login);
            }
            throw e;
        }
    }

    /** Same order and conditions as CharacterCreate.initNewChar (stock), without the client parts. */
    private static void initNewChar(Player newChar) {
        World.addObject(newChar);
        if (PlayerConfig.STARTING_ADENA > 0) {
            newChar.addAdena(ItemProcessType.REWARD, PlayerConfig.STARTING_ADENA, null, false);
        }
        final PlayerTemplate template = newChar.getTemplate();
        if (StartingLocationConfig.CUSTOM_STARTING_LOC) {
            newChar.setXYZInvisible(StartingLocationConfig.CUSTOM_STARTING_LOC_X, StartingLocationConfig.CUSTOM_STARTING_LOC_Y, StartingLocationConfig.CUSTOM_STARTING_LOC_Z);
        } else if (FactionSystemConfig.FACTION_SYSTEM_ENABLED) {
            newChar.setXYZInvisible(FactionSystemConfig.FACTION_STARTING_LOCATION.getX(), FactionSystemConfig.FACTION_STARTING_LOCATION.getY(), FactionSystemConfig.FACTION_STARTING_LOCATION.getZ());
        } else {
            final Location createLoc = template.getCreationPoint();
            newChar.setXYZInvisible(createLoc.getX(), createLoc.getY(), createLoc.getZ());
        }
        newChar.setTitle(StartingTitleConfig.ENABLE_CUSTOM_STARTING_TITLE ? StartingTitleConfig.CUSTOM_STARTING_TITLE : "");
        if (PlayerConfig.ENABLE_VITALITY) {
            newChar.setVitalityPoints(Math.min(PlayerConfig.STARTING_VITALITY_POINTS, PlayerStat.MAX_VITALITY_POINTS), true);
        }
        if (PlayerConfig.STARTING_LEVEL > 1) {
            newChar.getStat().addLevel((byte) (PlayerConfig.STARTING_LEVEL - 1));
        }
        if (PlayerConfig.STARTING_SP > 0) {
            newChar.getStat().addSp(PlayerConfig.STARTING_SP);
        }
        final Collection<InitialEquipment> classEquipment = InitialEquipmentData.getInstance().getClassEquipment(newChar.getPlayerClass());
        if (classEquipment != null) {
            for (InitialEquipment equipment : classEquipment) {
                final Item item = newChar.getInventory().addItem(ItemProcessType.REWARD, equipment.getId(), equipment.getCount(), newChar, null);
                if (item == null) {
                    continue;
                }
                if (item.isEquipable() && equipment.isEquipped()) {
                    newChar.getInventory().equipItem(item);
                }
            }
        }
        for (SkillLearn skill : SkillTreeData.getInstance().getAvailableSkills(newChar, newChar.getPlayerClass(), false, true)) {
            newChar.addSkill(SkillData.getInstance().getSkill(skill.getSkillId(), skill.getSkillLevel()), true);
        }
        InitialShortcutData.getInstance().registerAllShortcuts(newChar);
        // tutorial quest: client only, skipped for bots
        if (EventDispatcher.getInstance().hasListener(EventType.ON_PLAYER_CREATE, Containers.Players())) {
            EventDispatcher.getInstance().notifyEvent(new OnPlayerCreate(newChar, newChar.getObjectId(), newChar.getName(), null), Containers.Players());
        }
        newChar.setOnlineStatus(true, false);
        Disconnection.of(newChar).storeAndDelete();
    }

    /** Stock rules for a new character name (CharacterCreate) plus existing names. */
    static boolean nameTaken(String name) {
        if ((name.length() > 16) || !StringUtil.isAlphaNumeric(name) || !ServerConfig.CHARNAME_TEMPLATE_PATTERN.matcher(name).matches()) {
            return true;
        }
        final String low = name.toLowerCase(Locale.ROOT);
        for (String st : PlayerConfig.FORBIDDEN_NAMES) {
            if (!st.isEmpty() && low.contains(st.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return (FakePlayerData.getInstance().getProperName(name) != null) || CharInfoTable.getInstance().doesCharNameExist(name);
    }

    private String newAccountName() throws Exception {
        for (int i = 0; i < 50; i++) {
            final StringBuilder sb = new StringBuilder("b");
            for (int k = 0; k < 10; k++) {
                sb.append(ACCOUNT_CHARS[secure.nextInt(ACCOUNT_CHARS.length)]);
            }
            final String login = sb.toString();
            if (!repo.accountExists(login)) {
                return login;
            }
        }
        throw new IllegalStateException("no free account name");
    }

    /** SHA-1/base64 like the login server stores passwords, of 32 random bytes nobody knows. */
    private String unusablePasswordHash() throws Exception {
        final byte[] raw = new byte[32];
        secure.nextBytes(raw);
        final String secret = Base64.getEncoder().encodeToString(raw);
        return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA").digest(secret.getBytes(StandardCharsets.UTF_8)));
    }
}
