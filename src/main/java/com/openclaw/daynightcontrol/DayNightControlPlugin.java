package com.openclaw.daynightcontrol;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;

public final class DayNightControlPlugin extends JavaPlugin implements TabExecutor {
    private static final long DAY_START = 0L;
    private static final long NIGHT_START = 13000L;
    private static final long FULL_DAY_TICKS = 24000L;
    private static final long DAY_SPAN = NIGHT_START - DAY_START;      // 0..12999
    private static final long NIGHT_SPAN = FULL_DAY_TICKS - NIGHT_START; // 13000..23999
    private static final double SERVER_TICKS_PER_SECOND = 20.0;

    private final Map<String, WorldSettings> worldSettings = new HashMap<>();
    private final Map<String, Double> timeRemainders = new HashMap<>();
    private final Map<String, Long> sleepReadySinceTicks = new HashMap<>();
    private final Set<String> sleepFastForwardWorlds = new HashSet<>();
    private long serverTicks;
    private final Set<String> worldsWithoutClock = new HashSet<>();
    private WorldSettings defaultSettings;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        Objects.requireNonNull(getCommand("dnc"), "dnc command missing from plugin.yml").setExecutor(this);
        Objects.requireNonNull(getCommand("dnc"), "dnc command missing from plugin.yml").setTabCompleter(this);

        Bukkit.getScheduler().runTaskTimer(this, this::tickWorlds, 1L, 1L);
        getLogger().info("DayNightControl enabled.");
    }

    @Override
    public void onDisable() {
        for (World world : Bukkit.getWorlds()) {
            restoreDaylightCycle(world);
        }
    }

    private void loadSettings() {
        reloadConfig();
        defaultSettings = readSettings(getConfig().getConfigurationSection("default"), new WorldSettings(true, 10.0, 10.0, 1.0, 600.0));
        worldSettings.clear();

        ConfigurationSection worlds = getConfig().getConfigurationSection("worlds");
        if (worlds != null) {
            for (String worldName : worlds.getKeys(false)) {
                worldSettings.put(worldName.toLowerCase(Locale.ROOT), readSettings(worlds.getConfigurationSection(worldName), defaultSettings));
            }
        }
    }

    private WorldSettings readSettings(ConfigurationSection section, WorldSettings fallback) {
        if (section == null) {
            return fallback;
        }
        return new WorldSettings(
                section.getBoolean("enabled", fallback.enabled()),
                readMinutes(section, "day-minutes", fallback.dayMinutes()),
                readMinutes(section, "night-minutes", fallback.nightMinutes()),
                readSleepDelaySeconds(section, fallback.sleepDelaySeconds()),
                readSleepFastForwardMultiplier(section, fallback.sleepFastForwardMultiplier())
        );
    }

    private double readMinutes(ConfigurationSection section, String key, double fallback) {
        double value = section.getDouble(key, fallback);
        if (!Double.isFinite(value) || value < 0.05 || value > 1440.0) {
            getLogger().warning(section.getCurrentPath() + "." + key + " has invalid value " + value
                    + "; using " + fallback + " instead. Allowed range: 0.05..1440 minutes.");
            return fallback;
        }
        return value;
    }

    private double readSleepDelaySeconds(ConfigurationSection section, double fallback) {
        double value = section.getDouble("sleep-delay-seconds", fallback);
        if (!Double.isFinite(value) || value < 0.0 || value > 60.0) {
            getLogger().warning(section.getCurrentPath() + ".sleep-delay-seconds has invalid value " + value
                    + "; using " + fallback + " instead. Allowed range: 0..60 seconds.");
            return fallback;
        }
        return value;
    }

    private double readSleepFastForwardMultiplier(ConfigurationSection section, double fallback) {
        double value = section.getDouble("sleep-fast-forward-multiplier", fallback);
        if (!Double.isFinite(value) || value < 1.0 || value > 10000.0) {
            getLogger().warning(section.getCurrentPath() + ".sleep-fast-forward-multiplier has invalid value " + value
                    + "; using " + fallback + " instead. Allowed range: 1..10000.");
            return fallback;
        }
        return value;
    }

    private WorldSettings settingsFor(World world) {
        return worldSettings.getOrDefault(world.getName().toLowerCase(Locale.ROOT), defaultSettings);
    }

    private void tickWorlds() {
        serverTicks++;
        for (World world : Bukkit.getWorlds()) {
            String key = world.getUID().toString();
            if (worldsWithoutClock.contains(key)) {
                continue;
            }

            WorldSettings settings = settingsFor(world);
            if (!settings.enabled()) {
                restoreDaylightCycle(world);
                timeRemainders.remove(key);
                sleepReadySinceTicks.remove(key);
                sleepFastForwardWorlds.remove(key);
                continue;
            }

            try {
                if (Boolean.TRUE.equals(world.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE))) {
                    world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
                }

                long time = Math.floorMod(world.getTime(), FULL_DAY_TICKS);
                boolean isDay = time < NIGHT_START;
                double targetMinutes = isDay ? settings.dayMinutes() : settings.nightMinutes();
                double span = isDay ? DAY_SPAN : NIGHT_SPAN;
                double increment = span / (targetMinutes * 60.0 * SERVER_TICKS_PER_SECOND);
                if (!isDay && (sleepFastForwardWorlds.contains(key) || enoughPlayersAreSleeping(world, key, settings))) {
                    increment *= settings.sleepFastForwardMultiplier();
                }

                double totalIncrement = timeRemainders.getOrDefault(key, 0.0) + increment;
                long wholeTicks = (long) Math.floor(totalIncrement);
                timeRemainders.put(key, totalIncrement - wholeTicks);

                if (wholeTicks > 0L) {
                    long before = Math.floorMod(world.getTime(), FULL_DAY_TICKS);
                    world.setFullTime(world.getFullTime() + wholeTicks);
                    long after = Math.floorMod(world.getTime(), FULL_DAY_TICKS);
                    if (before >= NIGHT_START && after < NIGHT_START) {
                        if (sleepReadySinceTicks.containsKey(key)) {
                            finishSleepFastForward(world, key);
                        } else {
                            sleepReadySinceTicks.remove(key);
                            sleepFastForwardWorlds.remove(key);
                        }
                    }
                }
            } catch (IllegalArgumentException ex) {
                worldsWithoutClock.add(key);
                timeRemainders.remove(key);
                getLogger().warning("Skipping world without a controllable clock: " + world.getName() + " (" + ex.getMessage() + ")");
            }
        }
    }

    private void restoreDaylightCycle(World world) {
        try {
            if (Boolean.FALSE.equals(world.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE))) {
                world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, true);
            }
        } catch (IllegalArgumentException ignored) {
            // Some worlds may not expose this gamerule.
        }
    }

    private boolean enoughPlayersAreSleeping(World world, String key, WorldSettings settings) {
        long time = Math.floorMod(world.getTime(), FULL_DAY_TICKS);
        if (time < NIGHT_START) {
            sleepReadySinceTicks.remove(key);
            sleepFastForwardWorlds.remove(key);
            return false;
        }

        List<Player> players = world.getPlayers().stream()
                .filter(player -> player.getGameMode() != GameMode.SPECTATOR)
                .filter(player -> player.getGameMode() != GameMode.CREATIVE || player.isSleeping())
                .toList();
        if (players.isEmpty()) {
            sleepReadySinceTicks.remove(key);
            sleepFastForwardWorlds.remove(key);
            return false;
        }

        long sleeping = players.stream().filter(Player::isSleeping).count();
        if (sleeping <= 0 || sleeping * 100 < requiredSleepingPercentage(world) * players.size()) {
            sleepReadySinceTicks.remove(key);
            sleepFastForwardWorlds.remove(key);
            return false;
        }

        long readySince = sleepReadySinceTicks.computeIfAbsent(key, ignored -> serverTicks);
        long delayTicks = Math.round(settings.sleepDelaySeconds() * SERVER_TICKS_PER_SECOND);
        if (serverTicks - readySince < delayTicks) {
            return false;
        }

        sleepFastForwardWorlds.add(key);
        return true;
    }

    private void finishSleepFastForward(World world, String key) {
        world.setStorm(false);
        world.setThundering(false);
        timeRemainders.remove(key);
        sleepReadySinceTicks.remove(key);
        sleepFastForwardWorlds.remove(key);
        for (Player player : world.getPlayers()) {
            if (player.isSleeping()) {
                player.wakeup(false);
            }
        }
    }

    private int requiredSleepingPercentage(World world) {
        Integer value = world.getGameRuleValue(GameRule.PLAYERS_SLEEPING_PERCENTAGE);
        if (value == null) {
            return 100;
        }
        return Math.max(0, Math.min(100, value));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("daynightcontrol.admin")) {
            sender.sendMessage("§c권한이 없습니다: daynightcontrol.admin");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender, label);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> {
                World world = resolveWorld(sender, args.length >= 2 ? args[1] : null);
                if (world == null) return true;
                WorldSettings settings = settingsFor(world);
                sender.sendMessage("§e[DayNightControl] §f" + world.getName()
                        + " §7enabled=§f" + settings.enabled()
                        + " §7day=§f" + settings.dayMinutes() + "분"
                        + " §7night=§f" + settings.nightMinutes() + "분"
                        + " §7sleep-delay=§f" + settings.sleepDelaySeconds() + "초"
                        + " §7sleep-speed=§f" + settings.sleepFastForwardMultiplier() + "x");
                return true;
            }
            case "reload" -> {
                loadSettings();
                sender.sendMessage("§a[DayNightControl] config.yml을 다시 읽었습니다.");
                return true;
            }
            case "set" -> {
                return handleSet(sender, args);
            }
            default -> {
                sendHelp(sender, label);
                return true;
            }
        }
    }

    private boolean handleSet(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§c사용법: /dnc set <day|night|enabled|sleep-delay|sleep-speed> <값> [world]");
            return true;
        }

        World world = resolveWorld(sender, args.length >= 4 ? args[3] : null);
        if (world == null) return true;

        String path = "worlds." + world.getName() + ".";
        WorldSettings current = settingsFor(world);

        String field = args[1].toLowerCase(Locale.ROOT);
        try {
            switch (field) {
                case "day" -> {
                    ensureWorldConfigScaffold(path, current);
                    double minutes = parseMinutes(args[2]);
                    getConfig().set(path + "day-minutes", minutes);
                    sender.sendMessage("§a[DayNightControl] §f" + world.getName() + "§a 낮 시간을 §f" + minutes + "분§a으로 설정했습니다.");
                }
                case "night" -> {
                    ensureWorldConfigScaffold(path, current);
                    double minutes = parseMinutes(args[2]);
                    getConfig().set(path + "night-minutes", minutes);
                    sender.sendMessage("§a[DayNightControl] §f" + world.getName() + "§a 밤 시간을 §f" + minutes + "분§a으로 설정했습니다.");
                }
                case "enabled" -> {
                    Boolean enabled = parseBooleanArg(args[2]);
                    if (enabled == null) {
                        sender.sendMessage("§cenabled 값은 true 또는 false만 사용할 수 있습니다.");
                        return true;
                    }
                    ensureWorldConfigScaffold(path, current);
                    getConfig().set(path + "enabled", enabled);
                    sender.sendMessage("§a[DayNightControl] §f" + world.getName() + "§a 설정을 §f" + enabled + "§a로 바꿨습니다.");
                }
                case "sleep-delay" -> {
                    ensureWorldConfigScaffold(path, current);
                    double seconds = parseSleepDelaySeconds(args[2]);
                    getConfig().set(path + "sleep-delay-seconds", seconds);
                    sender.sendMessage("§a[DayNightControl] §f" + world.getName() + "§a 수면 fast-forward 대기 시간을 §f" + seconds + "초§a로 설정했습니다.");
                }
                case "sleep-speed" -> {
                    ensureWorldConfigScaffold(path, current);
                    double multiplier = parseSleepFastForwardMultiplier(args[2]);
                    getConfig().set(path + "sleep-fast-forward-multiplier", multiplier);
                    sender.sendMessage("§a[DayNightControl] §f" + world.getName() + "§a 수면 fast-forward 속도를 §f" + multiplier + "x§a로 설정했습니다.");
                }
                default -> {
                    sender.sendMessage("§c항목은 day, night, enabled, sleep-delay, sleep-speed 중 하나여야 합니다.");
                    return true;
                }
            }
        } catch (IllegalArgumentException ex) {
            sender.sendMessage("§c" + ex.getMessage());
            return true;
        }

        saveConfig();
        loadSettings();
        return true;
    }

    private void ensureWorldConfigScaffold(String path, WorldSettings current) {
        getConfig().set(path + "enabled", current.enabled());
        getConfig().set(path + "day-minutes", current.dayMinutes());
        getConfig().set(path + "night-minutes", current.nightMinutes());
        getConfig().set(path + "sleep-delay-seconds", current.sleepDelaySeconds());
        getConfig().set(path + "sleep-fast-forward-multiplier", current.sleepFastForwardMultiplier());
    }

    private double parseMinutes(String raw) {
        double minutes;
        try {
            minutes = Double.parseDouble(raw);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("분 단위 숫자를 입력하세요. 예: 10, 20.5");
        }
        if (!Double.isFinite(minutes) || minutes < 0.05 || minutes > 1440.0) {
            throw new IllegalArgumentException("시간은 0.05분 이상, 1440분 이하로 입력하세요.");
        }
        return minutes;
    }

    private Boolean parseBooleanArg(String raw) {
        if (raw.equalsIgnoreCase("true")) return true;
        if (raw.equalsIgnoreCase("false")) return false;
        return null;
    }

    private double parseSleepDelaySeconds(String raw) {
        double seconds = parseDouble(raw, "초 단위 숫자를 입력하세요. 예: 1, 2.5");
        if (seconds < 0.0 || seconds > 60.0) {
            throw new IllegalArgumentException("수면 대기 시간은 0초 이상, 60초 이하로 입력하세요.");
        }
        return seconds;
    }

    private double parseSleepFastForwardMultiplier(String raw) {
        double multiplier = parseDouble(raw, "배속 숫자를 입력하세요. 예: 100, 600");
        if (multiplier < 1.0 || multiplier > 10000.0) {
            throw new IllegalArgumentException("수면 fast-forward 속도는 1배 이상, 10000배 이하로 입력하세요.");
        }
        return multiplier;
    }

    private double parseDouble(String raw, String errorMessage) {
        double value;
        try {
            value = Double.parseDouble(raw);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(errorMessage);
        }
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(errorMessage);
        }
        return value;
    }

    private World resolveWorld(CommandSender sender, String worldName) {
        if (worldName != null) {
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                sender.sendMessage("§c월드를 찾을 수 없습니다: " + worldName);
            }
            return world;
        }

        if (sender instanceof org.bukkit.entity.Player player) {
            return player.getWorld();
        }

        World world = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().getFirst();
        if (world == null) {
            sender.sendMessage("§c로드된 월드가 없습니다.");
        }
        return world;
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage("§eDayNightControl 명령어");
        sender.sendMessage("§f/" + label + " status [world] §7- 현재 설정 확인");
        sender.sendMessage("§f/" + label + " set day <분> [world] §7- 낮 길이 설정");
        sender.sendMessage("§f/" + label + " set night <분> [world] §7- 밤 길이 설정");
        sender.sendMessage("§f/" + label + " set enabled <true|false> [world] §7- 켜기/끄기");
        sender.sendMessage("§f/" + label + " set sleep-delay <초> [world] §7- 잠 인원 충족 후 fast-forward 대기 시간");
        sender.sendMessage("§f/" + label + " set sleep-speed <배속> [world] §7- 수면 중 밤 fast-forward 속도");
        sender.sendMessage("§f/" + label + " reload §7- config.yml 다시 읽기");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("daynightcontrol.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(List.of("status", "set", "reload", "help"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
            return filter(List.of("day", "night", "enabled", "sleep-delay", "sleep-speed"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("set") && args[1].equalsIgnoreCase("enabled")) {
            return filter(List.of("true", "false"), args[2]);
        }
        if ((args.length == 2 && args[0].equalsIgnoreCase("status")) || (args.length == 4 && args[0].equalsIgnoreCase("set"))) {
            return filter(Bukkit.getWorlds().stream().map(World::getName).toList(), args[args.length - 1]);
        }
        return List.of();
    }

    private List<String> filter(List<String> values, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(value);
            }
        }
        return result;
    }

    private record WorldSettings(boolean enabled, double dayMinutes, double nightMinutes,
                                 double sleepDelaySeconds, double sleepFastForwardMultiplier) {
    }
}
