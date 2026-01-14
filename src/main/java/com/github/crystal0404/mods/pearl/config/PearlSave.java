package com.github.crystal0404.mods.pearl.config;

import com.github.crystal0404.mods.pearl.ChunkUtils;
import com.github.crystal0404.mods.pearl.PearlChunkLoadingMod;
import com.google.common.io.Files;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Comparator;

public class PearlSave {
    private static Path PATH;
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    // 启动专用票据，有效期 100 tick (5秒)
    private static final ChunkTicketType<ChunkPos> BOOTSTRAP_TICKET = ChunkTicketType.create(
            "pearl_bootstrap",
            Comparator.comparingLong(ChunkPos::toLong),
            100
    );

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTING.register(
                server -> {
                    PATH = server.session.getDirectory().path().resolve("pearl/Pearl.json");
                    File file = PATH.toFile();
                    if (file.getParentFile().mkdirs()) {
                        PearlChunkLoadingMod.LOGGER.info("The configuration folder was successfully created");
                    }
                    try {
                        if (file.createNewFile()) {
                            PearlChunkLoadingMod.LOGGER.info("Pearl.json profile was successfully created");
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
        );
    }

    public static void save(ServerPlayerEntity serverPlayerEntity) throws IOException {
        NbtCompound nbt = getNbt();
        NbtList nbtList = new NbtList();
        if (!serverPlayerEntity.pearl$getEnderPearls().isEmpty()) {
            for (EnderPearlEntity pearl : serverPlayerEntity.pearl$getEnderPearls()) {
                if (pearl.isRemoved()) {
                    PearlChunkLoadingMod.LOGGER.warn("Trying to save removed ender pearl, skipping");
                } else {
                    NbtCompound nbtCompound = new NbtCompound();
                    pearl.saveNbt(nbtCompound);
                    NbtElement worldNbt = World.CODEC.encodeStart(NbtOps.INSTANCE, pearl.getWorld().getRegistryKey()).getOrThrow();
                    nbtCompound.put("ender_pearl_dimension", worldNbt);
                    nbtList.add(nbtCompound);
                }
            }
            nbt.put(serverPlayerEntity.getUuid().toString(), nbtList);
        } else {
            nbt.remove(serverPlayerEntity.getUuid().toString());
        }
        JsonElement json = NbtCompound.CODEC.encodeStart(JsonOps.INSTANCE, nbt).getOrThrow();
        File file = PATH.toFile();
        Files.asCharSink(file, StandardCharsets.UTF_8).write(gson.toJson(json));
    }

    public static void loadEnderPearls(ServerPlayerEntity serverPlayerEntity) throws IOException {
        System.out.println("PEARL_DEBUG: loadEnderPearls called for " + serverPlayerEntity.getName().getString());
        NbtCompound nbt = getNbt();
        NbtList nbtList = nbt.getList(serverPlayerEntity.getUuid().toString(), NbtElement.COMPOUND_TYPE);
        
        if (!nbtList.isEmpty()) {
            System.out.println("PEARL_DEBUG: Found " + nbtList.size() + " pearls to load.");
            nbtList.forEach(nbtElement -> {
                if (nbtElement instanceof NbtCompound nbtCompound) {
                    try {
                        loadEnderPearl(serverPlayerEntity, nbtCompound);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });
        } else {
            System.out.println("PEARL_DEBUG: No pearls found in storage for this player.");
        }
    }

    private static void loadEnderPearl(ServerPlayerEntity serverPlayerEntity, NbtCompound nbtCompound) {
        RegistryKey<World> worldKey = World.CODEC.parse(NbtOps.INSTANCE, nbtCompound.get("ender_pearl_dimension")).getOrThrow();
        ServerWorld serverWorld = serverPlayerEntity.getServerWorld().getServer().getWorld(worldKey);
        
        if (serverWorld != null) {
            // 1. 反序列化实体
            Entity entity = EntityType.loadEntityWithPassengers(
                    nbtCompound, serverWorld, entity1 -> entity1
            );
            
            if (entity != null) {
                // 2. 设置主人
                if (entity instanceof EnderPearlEntity enderPearl) {
                    enderPearl.setOwner(serverPlayerEntity);
                }

                ChunkPos chunkPos = entity.getChunkPos();
                System.out.println("PEARL_DEBUG: Loading pearl at " + chunkPos + " in " + worldKey.getValue());

                // 3. 添加强效启动票据 (5秒)
                serverWorld.getChunkManager().addTicket(BOOTSTRAP_TICKET, chunkPos, 2, chunkPos);
                // 添加普通续期票据
                ChunkUtils.addEnderPearlTicket(serverWorld, chunkPos);

                // 4. 强制加载区块
                serverWorld.getChunk(chunkPos.x, chunkPos.z);

                // 5. 添加实体
                if (!serverWorld.tryLoadEntity(entity)) {
                    if (!serverWorld.entityList.has(entity)) {
                        serverWorld.entityList.add(entity);
                        System.out.println("PEARL_DEBUG: FORCE ADDED pearl entity.");
                        PearlChunkLoadingMod.LOGGER.info("Force loaded pearl in {} at {}", worldKey.getValue(), chunkPos);
                    } else {
                        System.out.println("PEARL_DEBUG: Entity already in list.");
                    }
                } else {
                    System.out.println("PEARL_DEBUG: Standard load success.");
                }
            } else {
                System.out.println("PEARL_DEBUG: Failed to deserialize entity.");
            }
        } else {
            System.out.println("PEARL_DEBUG: World " + worldKey.getValue() + " not found.");
        }
    }

    private static @NotNull NbtCompound getNbt() throws IOException {
        File file = PATH.toFile();
        JsonElement json = gson.fromJson(Files.asCharSource(file, StandardCharsets.UTF_8).read(), JsonElement.class);
        return json == null ? new NbtCompound() : NbtCompound.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
    }
}
