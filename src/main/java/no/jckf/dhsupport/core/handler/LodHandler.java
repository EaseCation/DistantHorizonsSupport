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

package no.jckf.dhsupport.core.handler;

import no.jckf.dhsupport.core.Coordinates;
import no.jckf.dhsupport.core.DhSupport;
import no.jckf.dhsupport.core.configuration.Configuration;
import no.jckf.dhsupport.core.configuration.DhsConfig;
import no.jckf.dhsupport.core.dataobject.SectionPosition;
import no.jckf.dhsupport.core.message.plugin.ExceptionMessage;
import no.jckf.dhsupport.core.message.plugin.FullDataChunkMessage;
import no.jckf.dhsupport.core.message.plugin.FullDataSourceRequestMessage;
import no.jckf.dhsupport.core.message.plugin.FullDataSourceResponseMessage;
import no.jckf.dhsupport.core.world.WorldInterface;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

public class LodHandler
{
    public static int CHUNK_SIZE = 1024 * 16; // TODO: Configurable?

    protected DhSupport dhSupport;

    protected PluginMessageHandler pluginMessageHandler;

    public LodHandler(DhSupport dhSupport, PluginMessageHandler pluginMessageHandler)
    {
        this.dhSupport = dhSupport;
        this.pluginMessageHandler = pluginMessageHandler;
    }

    public void register()
    {
        this.pluginMessageHandler.getEventBus().registerHandler(FullDataSourceRequestMessage.class, (requestMessage) -> {
            //this.dhSupport.info("LOD request for " + requestMessage.getPosition().getX() + " " + requestMessage.getPosition().getZ());

            if (requestMessage.getPosition().getDetailLevel() != 6) {
                ExceptionMessage exceptionMessage = new ExceptionMessage();
                exceptionMessage.isResponseTo(requestMessage);
                exceptionMessage.setTypeId(ExceptionMessage.TYPE_SECTION_REQUIRES_SPLITTING);
                exceptionMessage.setMessage("Only detail level 6 is supported");
                this.pluginMessageHandler.sendPluginMessage(requestMessage.getSender(), exceptionMessage);
                return;
            }

            // TODO: Some sort of Player wrapper or interface object. Bukkit classes should not be imported here.
            Player player = Bukkit.getPlayer(requestMessage.getSender());
            if (player == null) {
                this.reject(requestMessage, "Player is no longer online");
                return;
            }
            UUID worldUuid = player.getWorld().getUID();

            WorldInterface world = this.dhSupport.getWorldInterface(worldUuid);
            if (world == null) {
                this.reject(requestMessage, "World is not registered with DH Support");
                return;
            }

            Configuration config = world.getConfig();
            Configuration playerConfig = this.dhSupport.getPlayerConfiguration(requestMessage.getSender());

            if (!config.getBool(DhsConfig.DISTANT_GENERATION_ENABLED) || !playerConfig.getBool(DhsConfig.DISTANT_GENERATION_ENABLED)) {
                ExceptionMessage exceptionMessage = new ExceptionMessage();
                exceptionMessage.isResponseTo(requestMessage);
                exceptionMessage.setTypeId(ExceptionMessage.TYPE_SECTION_REQUIRES_SPLITTING);
                exceptionMessage.setMessage("Server has disabled distant generation");
                this.pluginMessageHandler.sendPluginMessage(requestMessage.getSender(), exceptionMessage);
                return;
            }

            SectionPosition position = requestMessage.getPosition();

            String builderType = config.getString(DhsConfig.BUILDER_TYPE);
            boolean builderDisabled = builderType.equalsIgnoreCase("none");
            boolean paused = this.dhSupport.isPaused();

            int worldX = Coordinates.sectionToBlock(position.getX());
            int worldZ = Coordinates.sectionToBlock(position.getZ());

            Integer borderCenterX = world.getWorldBorderX();
            Integer borderCenterZ = world.getWorldBorderZ();
            Integer borderRadius = world.getWorldBorderRadius();

            if (borderCenterX != null && borderCenterZ != null && borderRadius != null) {
                int minX = borderCenterX - borderRadius;
                int maxX = borderCenterX + borderRadius;

                int minZ = borderCenterZ - borderRadius;
                int maxZ = borderCenterZ + borderRadius;

                int higherLodX = worldX + 64;
                int higherLodZ = worldZ + 64;

                if (higherLodX < minX || worldX > maxX || higherLodZ < minZ || worldZ > maxZ) {
                    ExceptionMessage exceptionMessage = new ExceptionMessage();
                    exceptionMessage.isResponseTo(requestMessage);
                    exceptionMessage.setTypeId(ExceptionMessage.TYPE_REQUEST_REJECTED);
                    exceptionMessage.setMessage("World border");
                    this.pluginMessageHandler.sendPluginMessage(requestMessage.getSender(), exceptionMessage);
                    return;
                }
            }

            /*boolean generate = config.getBool(DhsConfig.GENERATE_NEW_CHUNKS);

            if (!generate) {
                for (int relativeChunkX = 0; relativeChunkX < Lod.width / 16; relativeChunkX++) {
                    for (int relativeChunkZ = 0; relativeChunkZ < Lod.width / 16; relativeChunkZ++) {
                        if (!world.chunkExists(worldX + relativeChunkX * 16, worldZ + relativeChunkZ * 16)) {
                            ExceptionMessage exceptionMessage = new ExceptionMessage();
                            exceptionMessage.isResponseTo(requestMessage);
                            exceptionMessage.setTypeId(ExceptionMessage.TYPE_REQUEST_REJECTED);
                            exceptionMessage.setMessage("Fog of war");
                            this.pluginMessageHandler.sendPluginMessage(requestMessage.getSender(), exceptionMessage);
                            return;
                        }
                    }
                }
            }*/

            if (!builderDisabled && !paused) {
                this.serveLod(requestMessage, worldUuid, position, playerConfig);
                return;
            }

            this.dhSupport.getLodRepository()
                .lodExistsAsync(worldUuid, position.getX(), position.getZ())
                .thenAccept((exists) -> {
                    if (!exists) {
                        this.reject(
                            requestMessage,
                            builderDisabled ? "Server has disabled LOD builder" : "Server has paused Distant Horizons"
                        );
                        return;
                    }
                    this.serveLod(requestMessage, worldUuid, position, playerConfig);
                })
                .exceptionally((exception) -> {
                    this.reject(requestMessage, "Internal error");
                    return null;
                });
        });
    }

    protected void serveLod(
        FullDataSourceRequestMessage requestMessage,
        UUID worldUuid,
        SectionPosition position,
        Configuration playerConfig
    )
    {
        this.dhSupport.getLod(worldUuid, position)
            .thenAccept((lodModel) -> {
                if (lodModel == null) {
                    this.reject(requestMessage, "No LOD available");
                    return;
                }

                FullDataSourceResponseMessage responseMessage = new FullDataSourceResponseMessage();
                responseMessage.isResponseTo(requestMessage);
                boolean sendData = requestMessage.getTimestamp() == null ||
                    (requestMessage.getTimestamp() / 1000) < lodModel.getTimestamp();

                if (sendData) {
                    int myBufferId = playerConfig.increment("buffer-id");
                    responseMessage.setBufferId(myBufferId);
                    responseMessage.setBeacons(lodModel.getBeacons());
                    byte[] data = lodModel.getData();
                    int chunkCount = (int) Math.ceil((double) data.length / CHUNK_SIZE);

                    for (int chunkNo = 0; chunkNo < chunkCount; chunkNo++) {
                        FullDataChunkMessage chunkResponse = new FullDataChunkMessage();
                        chunkResponse.setBufferId(myBufferId);
                        chunkResponse.setIsFirst(chunkNo == 0);
                        chunkResponse.setData(
                            data,
                            CHUNK_SIZE * chunkNo,
                            Math.min(CHUNK_SIZE, data.length - CHUNK_SIZE * chunkNo)
                        );
                        this.pluginMessageHandler.sendPluginMessage(requestMessage.getSender(), chunkResponse);
                    }
                }

                this.pluginMessageHandler.sendPluginMessage(requestMessage.getSender(), responseMessage);
            })
            .exceptionally((exception) -> {
                this.reject(requestMessage, "Internal error");
                return null;
            });
    }

    protected void reject(FullDataSourceRequestMessage requestMessage, String message)
    {
        ExceptionMessage exceptionMessage = new ExceptionMessage();
        exceptionMessage.isResponseTo(requestMessage);
        exceptionMessage.setTypeId(ExceptionMessage.TYPE_REQUEST_REJECTED);
        exceptionMessage.setMessage(message);
        this.pluginMessageHandler.sendPluginMessage(requestMessage.getSender(), exceptionMessage);
    }
}
