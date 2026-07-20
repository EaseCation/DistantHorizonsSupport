/*
 * DH Support, server-side support for Distant Horizons.
 * Copyright (C) 2024 Jim C K Flaten
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package no.jckf.dhsupport.bukkit;

import no.jckf.dhsupport.bukkit.commands.DhCommand;
import no.jckf.dhsupport.bukkit.commands.DhsCommand;
import no.jckf.dhsupport.bukkit.handler.ConfigLoader;
import no.jckf.dhsupport.bukkit.handler.PlayerHandler;
import no.jckf.dhsupport.bukkit.handler.PluginMessageProxy;
import no.jckf.dhsupport.bukkit.handler.WorldHandler;
import no.jckf.dhsupport.core.DhSupport;
import no.jckf.dhsupport.core.world.WorldInterface;
import net.easecation.dhsupport.api.DhSupportRuntimeApi;
import net.easecation.dhsupport.api.DhWorldRegistration;
import no.jckf.dhsupport.core.configuration.Configuration;
import no.jckf.dhsupport.core.configuration.DhsConfig;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.MultiLineChart;
import org.bstats.charts.SingleLineChart;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.ServicePriority;

import javax.annotation.Nullable;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

public class DhSupportBukkitPlugin extends JavaPlugin
{
    protected DhSupport dhSupport;

    protected Metrics metrics;

    protected ConfigLoader configLoader;

    protected PluginMessageProxy pluginMessageProxy;

    protected BukkitScheduler scheduler;

    protected BukkitDhSupportRuntimeApi runtimeApi;

    @Override
    public void onEnable()
    {
        String pluginVersion = "N/A";

        try {
            YamlConfiguration meta = new YamlConfiguration();
            meta.load(new InputStreamReader(this.getResource("plugin.yml")));

            pluginVersion = meta.getString("version");
        } catch (Exception exception) {

        }

        String gameVersion = this.getGameVersion();

        this.getLogger().info("I am DHS " + pluginVersion + " running on " + this.getServer().getName() + " for Minecraft " + gameVersion + ".");

        this.dhSupport = new DhSupport(pluginVersion, gameVersion);
        this.dhSupport.setLogger(this.getLogger());
        this.dhSupport.setDataDirectory(this.getDataFolder().getAbsolutePath());

        this.metrics = new Metrics(this, 21843);
        this.metrics.addCustomChart(new SingleLineChart("dh_users", () -> this.dhSupport.getPlayerConfigurations().size()));
        this.metrics.addCustomChart(new MultiLineChart("dh_render_distance", () -> {
            Map<String, Integer> renderDistances = new HashMap<>();

            for (Configuration playerConfig : this.dhSupport.getPlayerConfigurations().values()) {
                String rd = String.valueOf(playerConfig.getInt(DhsConfig.RENDER_DISTANCE));

                renderDistances.put(rd, renderDistances.getOrDefault(rd, 0) + 1);
            }

            return renderDistances;
        }));

        this.loadDhsConfig();

        this.pluginMessageProxy = new PluginMessageProxy(this);
        this.pluginMessageProxy.onEnable();

        this.scheduler = new BukkitScheduler(this);
        this.dhSupport.setScheduler(this.scheduler);

        this.dhSupport.onEnable();

        int lodRefreshInterval = this.getDhSupport().getConfig().getInt(DhsConfig.LOD_REFRESH_INTERVAL) * 20;

        this.scheduler.runTimer(() -> {
            this.dhSupport.getGenerationTracker().prune();
            this.dhSupport.updateTouchedLods();
        }, lodRefreshInterval, lodRefreshInterval);

        this.getServer().getPluginManager().registerEvents(new WorldHandler(this), this);
        this.getServer().getPluginManager().registerEvents(new PlayerHandler(this), this);

        this.runtimeApi = new BukkitDhSupportRuntimeApi(this);
        this.getServer().getServicesManager().register(
            DhSupportRuntimeApi.class,
            this.runtimeApi,
            this,
            ServicePriority.Normal
        );

        this.getCommand("dhs").setExecutor(new DhsCommand(this));
        this.getCommand("dh").setExecutor(new DhCommand(this));

        this.getLogger().info("Ready!");
    }

    @Override
    public void onDisable()
    {
        this.getServer().getServicesManager().unregisterAll(this);
        this.runtimeApi = null;

        if (this.metrics != null) {
            this.metrics.shutdown();
        }

        if (this.scheduler != null) {
            this.scheduler.cancelTasks();
        }

        if (this.pluginMessageProxy != null) {
            this.pluginMessageProxy.onDisable();
            this.pluginMessageProxy = null;
        }

        if (this.configLoader != null) {
            this.configLoader.onDisable();
            this.configLoader = null;
        }

        if (this.dhSupport != null) {
            this.dhSupport.onDisable();
            this.dhSupport = null;
        }

        this.getLogger().info("Lights out!");
    }

    public void loadDhsConfig()
    {
        if (this.configLoader != null) {
            this.configLoader.onDisable();
        }

        this.configLoader = new ConfigLoader(this);
        this.configLoader.onEnable();
    }

    public @Nullable World getWorld(String name)
    {
        for (World world : this.getServer().getWorlds()) {
            if (world.getName().replace(' ', '_').equals(name)) {
                return world;
            }
        }

        return null;
    }

    protected String getGameVersion()
    {
        try {
            Class<? extends Server> serverClass = this.getServer().getClass();

            serverClass.getMethod("getMinecraftVersion");

            return this.getServer().getMinecraftVersion();
        } catch (NoSuchMethodException exception) {
            // If we're here, that probably means we're not running Paper or any Paper fork.
        }

        return this.getServer().getBukkitVersion().split("-", 2)[0];
    }

    @Nullable
    public DhSupport getDhSupport()
    {
        return this.dhSupport;
    }

    public synchronized DhWorldRegistration ensureWorldRegistered(World world)
    {
        if (this.dhSupport == null) {
            throw new IllegalStateException("DH Support is not enabled");
        }

        WorldInterface existing = this.dhSupport.getWorldInterface(world.getUID());
        if (existing != null) {
            if (!existing.getName().equals(world.getName())) {
                throw new IllegalStateException(
                    "World UUID " + world.getUID() + " is already registered as " + existing.getName()
                );
            }
            return new DhWorldRegistration(world.getUID(), world.getName(), false);
        }

        BukkitWorldInterface worldInterface = new BukkitWorldInterface(this, world, this.dhSupport.getConfig());
        worldInterface.setLogger(this.dhSupport.getLogger());
        worldInterface.doUnsafeThings();
        this.dhSupport.setWorldInterface(world.getUID(), worldInterface);
        this.getLogger().info("Registered DH world interface for " + world.getName() + " (" + world.getUID() + ")");
        return new DhWorldRegistration(world.getUID(), world.getName(), true);
    }
}
