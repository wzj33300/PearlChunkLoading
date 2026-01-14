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
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public class PearlSave {
    private static Path PATH;
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

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
        NbtCompound nbt = getNbt();
        NbtList nbtList = nbt.getList(serverPlayerEntity.getUuid().toString(), NbtElement.COMPOUND_TYPE);
        if (!nbtList.isEmpty()) {
            nbtList.forEach(nbtElement -> {
                if (nbtElement instanceof NbtCompound nbtCompound) {
                    loadEnderPearl(serverPlayerEntity, nbtCompound);
                }
            });
        }
    }

    private static void loadEnderPearl(
            ServerPlayerEntity serverPlayerEntity,
            NbtCompound nbtCompound
    ) {
        RegistryKey<World> world = World.CODEC.parse(NbtOps.INSTANCE, nbtCompound.get("ender_pearl_dimension")).getOrThrow();
        ServerWorld serverWorld = serverPlayerEntity.getServerWorld().getServer().getWorld(world);
        if (serverWorld != null) {
            // 1. 反序列化实体，但不立即加入世界
            Entity entity = EntityType.loadEntityWithPassengers(
                    nbtCompound, serverWorld, entity1 -> entity1
            );
            
            if (entity != null) {
                // [关键修复]：手动设置主人为当前正在登录的玩家。
                // 否则，因为玩家此时还不在服务器的全局列表中，pearl.getOwner() 会返回 null，
                // 导致 Mixin 中的票据续期逻辑失效。
                if (entity instanceof EnderPearlEntity enderPearl) {
                    enderPearl.setOwner(serverPlayerEntity);
                }

                // 2. 添加票据并强制加载区块
                ChunkUtils.addEnderPearlTicket(serverWorld, entity.getChunkPos());
                serverWorld.getChunk(entity.getChunkPos().x, entity.getChunkPos().z);

                // 3. 将实体加入世界
                if (!serverWorld.tryLoadEntity(entity)) {
                    // 如果标准加载失败（极少见），尝试强制添加
                    if (!serverWorld.entityList.has(entity)) {
                        serverWorld.entityList.add(entity);
                    } else {
                        PearlChunkLoadingMod.LOGGER.warn(
                            "Failed to spawn player ender pearl in level ({})",
                            world
                        );
                    }
                }
            } else {
                PearlChunkLoadingMod.LOGGER.warn(
                        "Failed to deserialize player ender pearl in level ({}), skipping",
                        world
                );
            }
        } else {
            PearlChunkLoadingMod.LOGGER.warn(
                    "Trying to load ender pearl without level ({}) being loaded, skipping",
                    world
            );
        }

    }

    private static @NotNull NbtCompound getNbt() throws IOException {
        File file = PATH.toFile();
        JsonElement json = gson.fromJson(Files.asCharSource(file, StandardCharsets.UTF_8).read(), JsonElement.class);
        return json == null ? new NbtCompound() : NbtCompound.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
    }
}
