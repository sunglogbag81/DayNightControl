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
    private static final long MORNING_TIME = 0L;
    private static final long DAY_SPAN = NIGHT_START - DAY_START;      // 0..12999
    private static final long NIGHT_SPAN = FULL_DAY_TICKS - NIGHT_START; // 13000..23999
    private static final double SERVER_TICKS_PER_SECOND = 20.0;

    private final Map<String, WorldSettings> worldSettings = new HashMap<>();
    private final Map<String, Double> timeRemainders = new HashMap<>();
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

    private void loadSettings() {
        reloadConfig();
        defaultSettings = readSettings(getConfig().getConfigurationSection("default"), new WorldSettings(true, 10.0, 10.0));
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
                Math.max(0.05, section.getDouble("day-minutes", fallback.dayMinutes())),
                Math.max(0.05, section.getDouble("night-minutes", fallback.nightMinutes()))
        );
    }

    private WorldSettings settingsFor(World world) {
        return worldSettings.getOrDefault(world.getName().toLowerCase(Locale.ROOT), defaultSettings);
    }

    private void tickWorlds() {
        for (World world : Bukkit.getWorlds()) {
            String key = world.getUID().toString();
            if (worldsWithoutClock.contains(key)) {
                continue;
            }

            WorldSettings settings = settingsFor(world);
            if (!settings.enabled()) {
                continue;
            }

            try {
                if (Boolean.TRUE.equals(world.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE))) {
                    world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
                }

                if (skipNightIfEnoughPlayersAreSleeping(world, key)) {
                    continue;
                }

                long time = Math.floorMod(world.getTime(), FULL_DAY_TICKS);
                boolean isDay = time < NIGHT_START;
                double targetMinutes = isDay ? settings.dayMinutes() : settings.nightMinutes();
                double span = isDay ? DAY_SPAN : NIGHT_SPAN;
                double increment = span / (targetMinutes * 60.0 * SERVER_TICKS_PER_SECOND);

                double totalIncrement = timeRemainders.getOrDefault(key, 0.0) + increment;
                long wholeTicks = (long) Math.floor(totalIncrement);
                timeRemainders.put(key, totalIncrement - wholeTicks);

                if (wholeTicks > 0L) {
                    world.setFullTime(world.getFullTime() + wholeTicks);
                }
            } catch (IllegalArgumentException ex) {
                worldsWithoutClock.add(key);
                timeRemainders.remove(key);
                getLogger().warning("Skipping world without a controllable clock: " + world.getName() + " (" + ex.getMessage() + ")");
            }
        }
    }

    private boolean skipNightIfEnoughPlayersAreSleeping(World world, String key) {
        long time = Math.floorMod(world.getTime(), FULL_DAY_TICKS);
        if (time < NIGHT_START) {
            return false;
        }

        List<Player> players = world.getPlayers().stream()
                .filter(player -> player.getGameMode() != GameMode.SPECTATOR)
                .filter(player -> player.getGameMode() != GameMode.CREATIVE || player.isSleeping())
                .toList();
        if (players.isEmpty()) {
            return false;
        }

        long sleeping = players.stream().filter(Player::isSleeping).count();
        if (sleeping <= 0 || sleeping * 100 < requiredSleepingPercentage(world) * players.size()) {
            return false;
        }

        long fullTime = world.getFullTime();
        long currentTime = Math.floorMod(fullTime, FULL_DAY_TICKS);
        world.setFullTime(fullTime - currentTime + FULL_DAY_TICKS + MORNING_TIME);
        world.setStorm(false);
        world.setThundering(false);
        timeRemainders.remove(key);
        for (Player player : world.getPlayers()) {
            if (player.isSleeping()) {
                player.wakeup(false);
            }
        }
        return true;
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
                        + " §7night=§f" + settings.nightMinutes() + "분");
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
            sender.sendMessage("§c사용법: /dnc set <day|night|enabled> <값> [world]");
            return true;
        }

        World world = resolveWorld(sender, args.length >= 4 ? args[3] : null);
        if (world == null) return true;

        String path = "worlds." + world.getName() + ".";
        WorldSettings current = settingsFor(world);
        getConfig().set(path + "enabled", current.enabled());
        getConfig().set(path + "day-minutes", current.dayMinutes());
        getConfig().set(path + "night-minutes", current.nightMinutes());

        String field = args[1].toLowerCase(Locale.ROOT);
        try {
            switch (field) {
                case "day" -> {
                    double minutes = parseMinutes(args[2]);
                    getConfig().set(path + "day-minutes", minutes);
                    sender.sendMessage("§a[DayNightControl] §f" + world.getName() + "§a 낮 시간을 §f" + minutes + "분§a으로 설정했습니다.");
                }
                case "night" -> {
                    double minutes = parseMinutes(args[2]);
                    getConfig().set(path + "night-minutes", minutes);
                    sender.sendMessage("§a[DayNightControl] §f" + world.getName() + "§a 밤 시간을 §f" + minutes + "분§a으로 설정했습니다.");
                }
                case "enabled" -> {
                    boolean enabled = Boolean.parseBoolean(args[2]);
                    getConfig().set(path + "enabled", enabled);
                    sender.sendMessage("§a[DayNightControl] §f" + world.getName() + "§a 설정을 §f" + enabled + "§a로 바꿨습니다.");
                }
                default -> sender.sendMessage("§c항목은 day, night, enabled 중 하나여야 합니다.");
            }
        } catch (IllegalArgumentException ex) {
            sender.sendMessage("§c" + ex.getMessage());
            return true;
        }

        saveConfig();
        loadSettings();
        return true;
    }

    private double parseMinutes(String raw) {
        double minutes;
        try {
            minutes = Double.parseDouble(raw);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("분 단위 숫자를 입력하세요. 예: 10, 20.5");
        }
        if (minutes < 0.05 || minutes > 1440.0) {
            throw new IllegalArgumentException("시간은 0.05분 이상, 1440분 이하로 입력하세요.");
        }
        return minutes;
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
            return filter(List.of("day", "night", "enabled"), args[1]);
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

    private record WorldSettings(boolean enabled, double dayMinutes, double nightMinutes) {
    }
}
