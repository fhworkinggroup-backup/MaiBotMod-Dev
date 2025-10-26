package com.maibotmod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.minecraftforge.server.command.ConfigCommand;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@Mod(MaiBotMod.MODID)
public class MaiBotMod {
    public static final String MODID = "maibotmod";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().create();
    private static final AtomicLong MESSAGE_ID_COUNTER = new AtomicLong(1);

    // 默认 WebSocket 服务器地址
    private static final String DEFAULT_WS_SERVER = "ws://localhost:8765";

    // 实体注册
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MODID);

    public static final RegistryObject<EntityType<XiaoMaiEntity>> XIAO_MAI =
            ENTITIES.register("xiao_mai",
                    () -> EntityType.Builder.of(XiaoMaiEntity::new, MobCategory.CREATURE)
                            .sized(0.6f, 1.8f) // 玩家尺寸
                            .build("xiao_mai"));

    // 模型层位置
    public static final ModelLayerLocation XIAO_MAI_LAYER =
            new ModelLayerLocation(new ResourceLocation(MODID, "xiao_mai"), "main");

    private WebSocketClient maibotClient;
    private boolean isConnectedToMaibot = false;
    private XiaoMaiEntity xiaoMaiEntity;
    private UUID followingPlayerId;
    private String currentWebSocketServer = DEFAULT_WS_SERVER;

    public MaiBotMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // 注册实体
        ENTITIES.register(modEventBus);

        // 注册实体属性事件
        modEventBus.addListener(this::registerAttributes);

        // 如果是客户端，注册渲染器
        if (FMLEnvironment.dist == Dist.CLIENT) {
            modEventBus.addListener(this::registerRenderers);
            modEventBus.addListener(this::registerLayerDefinitions);
        }

        MinecraftForge.EVENT_BUS.register(this);
    }

    // 注册实体属性
    public void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(XIAO_MAI.get(), XiaoMaiEntity.createAttributes().build());
    }

    // 注册渲染器（仅客户端）
    @SubscribeEvent
    public void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(XIAO_MAI.get(), XiaoMaiRenderer::new);
    }

    // 注册模型层定义（仅客户端）
    @SubscribeEvent
    public void registerLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(XIAO_MAI_LAYER, XiaoMaiModel::createBodyLayer);
    }

    // 自定义实体类
    public static class XiaoMaiEntity extends PathfinderMob {
        public XiaoMaiEntity(EntityType<? extends PathfinderMob> entityType, Level level) {
            super(entityType, level);
            this.setCustomName(net.minecraft.network.chat.Component.literal("XiaoMai"));
            this.setCustomNameVisible(true);
        }

        public static AttributeSupplier.Builder createAttributes() {
            return Mob.createMobAttributes()
                    .add(Attributes.MAX_HEALTH, 100.0D)
                    .add(Attributes.MOVEMENT_SPEED, 0.3D)
                    .add(Attributes.FOLLOW_RANGE, 32.0D)
                    .add(Attributes.ATTACK_DAMAGE, 0.0D);
        }

        @Override
        protected void registerGoals() {
            this.goalSelector.addGoal(1, new LookAtPlayerGoal(this, Player.class, 8.0F));
            this.goalSelector.addGoal(2, new RandomStrollGoal(this, 0.6D));
        }

        @Override
        public boolean removeWhenFarAway(double distanceToClosestPlayer) {
            return false;
        }

        @Override
        public boolean isInvulnerable() {
            return true;
        }

        @Override
        public boolean isPushedByFluid() {
            return false;
        }

        @Override
        protected boolean shouldDespawnInPeaceful() {
            return false;
        }
    }

    // 自定义模型类
    @OnlyIn(Dist.CLIENT)
    public static class XiaoMaiModel<T extends Entity> extends EntityModel<T> {
        private final ModelPart head;
        private final ModelPart body;
        private final ModelPart rightArm;
        private final ModelPart leftArm;
        private final ModelPart rightLeg;
        private final ModelPart leftLeg;

        public XiaoMaiModel(ModelPart root) {
            this.head = root.getChild("head");
            this.body = root.getChild("body");
            this.rightArm = root.getChild("rightArm");
            this.leftArm = root.getChild("leftArm");
            this.rightLeg = root.getChild("rightLeg");
            this.leftLeg = root.getChild("leftLeg");
        }

        public static LayerDefinition createBodyLayer() {
            MeshDefinition meshdefinition = new MeshDefinition();
            PartDefinition partdefinition = meshdefinition.getRoot();

            // 头部
            PartDefinition head = partdefinition.addOrReplaceChild("head", CubeListBuilder.create()
                            .texOffs(0, 0).addBox(-4.0F, -8.0F, -4.0F, 8.0F, 8.0F, 8.0F, new CubeDeformation(0.0F))
                            .texOffs(32, 0).addBox(-4.0F, -8.0F, -4.0F, 8.0F, 8.0F, 8.0F, new CubeDeformation(0.5F)),
                    PartPose.offset(0.0F, 0.0F, 0.0F));

            // 身体
            PartDefinition body = partdefinition.addOrReplaceChild("body", CubeListBuilder.create()
                            .texOffs(16, 16).addBox(-4.0F, 0.0F, -2.0F, 8.0F, 12.0F, 4.0F, new CubeDeformation(0.0F))
                            .texOffs(16, 32).addBox(-4.0F, 0.0F, -2.0F, 8.0F, 12.0F, 4.0F, new CubeDeformation(0.25F)),
                    PartPose.offset(0.0F, 0.0F, 0.0F));

            // 右臂
            PartDefinition rightArm = partdefinition.addOrReplaceChild("rightArm", CubeListBuilder.create()
                            .texOffs(40, 16).addBox(-3.0F, -2.0F, -2.0F, 4.0F, 12.0F, 4.0F, new CubeDeformation(0.0F))
                            .texOffs(40, 32).addBox(-3.0F, -2.0F, -2.0F, 4.0F, 12.0F, 4.0F, new CubeDeformation(0.25F)),
                    PartPose.offset(-5.0F, 2.0F, 0.0F));

            // 左臂
            PartDefinition leftArm = partdefinition.addOrReplaceChild("leftArm", CubeListBuilder.create()
                            .texOffs(32, 48).addBox(-1.0F, -2.0F, -2.0F, 4.0F, 12.0F, 4.0F, new CubeDeformation(0.0F))
                            .texOffs(48, 48).addBox(-1.0F, -2.0F, -2.0F, 4.0F, 12.0F, 4.0F, new CubeDeformation(0.25F)),
                    PartPose.offset(5.0F, 2.0F, 0.0F));

            // 右腿
            PartDefinition rightLeg = partdefinition.addOrReplaceChild("rightLeg", CubeListBuilder.create()
                            .texOffs(0, 16).addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F, new CubeDeformation(0.0F))
                            .texOffs(0, 32).addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F, new CubeDeformation(0.25F)),
                    PartPose.offset(-1.9F, 12.0F, 0.0F));

            // 左腿
            PartDefinition leftLeg = partdefinition.addOrReplaceChild("leftLeg", CubeListBuilder.create()
                            .texOffs(16, 48).addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F, new CubeDeformation(0.0F))
                            .texOffs(0, 48).addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F, new CubeDeformation(0.25F)),
                    PartPose.offset(1.9F, 12.0F, 0.0F));

            return LayerDefinition.create(meshdefinition, 64, 64);
        }

        @Override
        public void setupAnim(T entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
            // 头部旋转
            this.head.yRot = netHeadYaw * ((float)Math.PI / 180F);
            this.head.xRot = headPitch * ((float)Math.PI / 180F);

            // 手臂和腿部动画
            this.rightArm.xRot = (float)Math.cos(limbSwing * 0.6662F + (float)Math.PI) * 2.0F * limbSwingAmount * 0.5F;
            this.leftArm.xRot = (float)Math.cos(limbSwing * 0.6662F) * 2.0F * limbSwingAmount * 0.5F;
            this.rightLeg.xRot = (float)Math.cos(limbSwing * 0.6662F) * 1.4F * limbSwingAmount;
            this.leftLeg.xRot = (float)Math.cos(limbSwing * 0.6662F + (float)Math.PI) * 1.4F * limbSwingAmount;
        }

        @Override
        public void renderToBuffer(PoseStack poseStack, VertexConsumer vertexConsumer, int packedLight, int packedOverlay, float red, float green, float blue, float alpha) {
            head.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
            body.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
            rightArm.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
            leftArm.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
            rightLeg.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
            leftLeg.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
        }
    }

    // 自定义渲染器
    @OnlyIn(Dist.CLIENT)
    public static class XiaoMaiRenderer extends MobRenderer<XiaoMaiEntity, XiaoMaiModel<XiaoMaiEntity>> {
        private static final ResourceLocation TEXTURE =
                new ResourceLocation(MODID, "textures/entity/xiao_mai.png");

        public XiaoMaiRenderer(EntityRendererProvider.Context context) {
            super(context, new XiaoMaiModel<>(context.bakeLayer(XIAO_MAI_LAYER)), 0.5f);
        }

        @Override
        public ResourceLocation getTextureLocation(XiaoMaiEntity entity) {
            return TEXTURE;
        }
    }

    // 以下保持原有命令和WebSocket逻辑不变...
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("MaiBot Mod server starting...");
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        LOGGER.info("MaiBot Mod server stopping...");
        disconnectFromMaibot();
        removeXiaoMaiEntity();
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            return;
        }

        if (xiaoMaiEntity != null && followingPlayerId != null &&
                ServerLifecycleHooks.getCurrentServer() != null) {

            Player targetPlayer = ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayer(followingPlayerId);
            if (targetPlayer != null && xiaoMaiEntity.isAlive()) {
                xiaoMaiEntity.getNavigation().moveTo(targetPlayer, 1.0);
                xiaoMaiEntity.getLookControl().setLookAt(targetPlayer, 30.0F, 30.0F);
            }
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        // /maibot connect 命令，支持指定 WebSocket 服务器地址
        event.getDispatcher().register(
                net.minecraft.commands.Commands.literal("maibot")
                        .then(net.minecraft.commands.Commands.literal("connect")
                                .executes(context -> {
                                    // 使用默认地址连接
                                    connectToMaibot(context.getSource(), DEFAULT_WS_SERVER);
                                    return 1;
                                })
                                .then(net.minecraft.commands.Commands.argument("ws_url", com.mojang.brigadier.arguments.StringArgumentType.string())
                                        .executes(context -> {
                                            String wsUrl = com.mojang.brigadier.arguments.StringArgumentType.getString(context, "ws_url");
                                            connectToMaibot(context.getSource(), wsUrl);
                                            return 1;
                                        })
                                )
                        )
        );

        event.getDispatcher().register(
                net.minecraft.commands.Commands.literal("maibot")
                        .then(net.minecraft.commands.Commands.literal("disconnect")
                                .executes(context -> {
                                    disconnectFromMaibot(context.getSource());
                                    return 1;
                                })
                        )
        );

        event.getDispatcher().register(
                net.minecraft.commands.Commands.literal("maigs")
                        .executes(context -> {
                            if (context.getSource().getEntity() instanceof Player) {
                                Player player = (Player) context.getSource().getEntity();
                                setFollowingPlayer(context.getSource(), player);
                                return 1;
                            } else {
                                context.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal("This command can only be used by players"), false);
                                return 0;
                            }
                        })
        );

        event.getDispatcher().register(
                net.minecraft.commands.Commands.literal("maibot")
                        .then(net.minecraft.commands.Commands.literal("spawn")
                                .executes(context -> {
                                    spawnXiaoMaiEntity(context.getSource());
                                    return 1;
                                })
                        )
        );

        event.getDispatcher().register(
                net.minecraft.commands.Commands.literal("maibot")
                        .then(net.minecraft.commands.Commands.literal("remove")
                                .executes(context -> {
                                    removeXiaoMaiEntity(context.getSource());
                                    return 1;
                                })
                        )
        );

        event.getDispatcher().register(
                net.minecraft.commands.Commands.literal("maibot")
                        .then(net.minecraft.commands.Commands.literal("status")
                                .executes(context -> {
                                    showStatus(context.getSource());
                                    return 1;
                                })
                        )
        );

        // 设置 WebSocket 服务器地址命令
        event.getDispatcher().register(
                net.minecraft.commands.Commands.literal("maibot")
                        .then(net.minecraft.commands.Commands.literal("set_server")
                                .then(net.minecraft.commands.Commands.argument("ws_url", com.mojang.brigadier.arguments.StringArgumentType.string())
                                        .executes(context -> {
                                            String wsUrl = com.mojang.brigadier.arguments.StringArgumentType.getString(context, "ws_url");
                                            setWebSocketServer(context.getSource(), wsUrl);
                                            return 1;
                                        })
                                )
                        )
        );

        // 显示当前服务器地址命令
        event.getDispatcher().register(
                net.minecraft.commands.Commands.literal("maibot")
                        .then(net.minecraft.commands.Commands.literal("server_info")
                                .executes(context -> {
                                    showServerInfo(context.getSource());
                                    return 1;
                                })
                        )
        );

        // 发送消息命令
        event.getDispatcher().register(
                net.minecraft.commands.Commands.literal("maibot")
                        .then(net.minecraft.commands.Commands.literal("send")
                                .then(net.minecraft.commands.Commands.argument("message", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                        .executes(context -> {
                                            String message = com.mojang.brigadier.arguments.StringArgumentType.getString(context, "message");
                                            sendChatMessage(context.getSource(), message);
                                            return 1;
                                        })
                                )
                        )
        );

        // 提问命令 - 更容易获得回复
        event.getDispatcher().register(
                net.minecraft.commands.Commands.literal("maibot")
                        .then(net.minecraft.commands.Commands.literal("ask")
                                .then(net.minecraft.commands.Commands.argument("question", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                        .executes(context -> {
                                            String question = com.mojang.brigadier.arguments.StringArgumentType.getString(context, "question");
                                            sendQuestionMessage(context.getSource(), question);
                                            return 1;
                                        })
                                )
                        )
        );

        event.getDispatcher().register(
                net.minecraft.commands.Commands.literal("maibot")
                        .then(net.minecraft.commands.Commands.literal("test")
                                .executes(context -> {
                                    sendTestMessage(context.getSource());
                                    return 1;
                                })
                        )
        );

        // Ping 命令
        event.getDispatcher().register(
                net.minecraft.commands.Commands.literal("maibot")
                        .then(net.minecraft.commands.Commands.literal("ping")
                                .executes(context -> {
                                    sendPingMessage(context.getSource());
                                    return 1;
                                })
                        )
        );

        // Help 命令
        event.getDispatcher().register(
                net.minecraft.commands.Commands.literal("maibot")
                        .then(net.minecraft.commands.Commands.literal("help")
                                .executes(context -> {
                                    showDetailedHelp(context.getSource());
                                    return 1;
                                })
                        )
        );

        event.getDispatcher().register(
                net.minecraft.commands.Commands.literal("maibot")
                        .executes(context -> {
                            showQuickHelp(context.getSource());
                            return 1;
                        })
        );

        ConfigCommand.register(event.getDispatcher());
    }

    private void connectToMaibot(net.minecraft.commands.CommandSourceStack source, String wsUrl) {
        if (isConnectedToMaibot) {
            source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("Already connected to MaiBot! Use /maibot disconnect first."), false);
            return;
        }

        try {
            // 验证 URL 格式
            if (!wsUrl.startsWith("ws://") && !wsUrl.startsWith("wss://")) {
                source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("Invalid WebSocket URL. Must start with ws:// or wss://"), false);
                return;
            }

            URI serverUri = new URI(wsUrl);
            currentWebSocketServer = wsUrl;

            maibotClient = new WebSocketClient(serverUri) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    isConnectedToMaibot = true;
                    LOGGER.info("Connected to MaiBot WebSocket server: {}", wsUrl);

                    if (ServerLifecycleHooks.getCurrentServer() != null) {
                        ServerLifecycleHooks.getCurrentServer().execute(() -> {
                            spawnXiaoMaiEntity();
                            // 发送符合 Maim_Message 标准的连接消息
                            Map<String, Object> connectMessage = createMaimMessage(
                                    "Minecraft server connected and XiaoMai spawned",
                                    "system",
                                    "Minecraft Server"
                            );
                            sendToMaiBot(GSON.toJson(connectMessage));

                            // 通知玩家连接成功
                            broadcastMessage("§aConnected to MaiBot server: " + wsUrl);
                        });
                    }
                }

                @Override
                public void onMessage(String message) {
                    LOGGER.info("Received MaiBot message from {}: {}", wsUrl, message);

                    // 解析 Maim_Message 格式的消息
                    try {
                        Map<String, Object> messageData = GSON.fromJson(message, Map.class);

                        // 提取消息基本信息
                        Map<String, Object> messageInfo = (Map<String, Object>) messageData.get("message_info");
                        String messageId = (String) messageInfo.get("message_id");
                        String platform = (String) messageInfo.get("platform");

                        // 提取消息内容
                        Map<String, Object> messageSegment = (Map<String, Object>) messageData.get("message_segment");
                        String segType = (String) messageSegment.get("type");
                        Object segData = messageSegment.get("data");

                        String displayMessage = extractTextFromSeg(segType, segData);

                        if (displayMessage != null && !displayMessage.isEmpty()) {
                            LOGGER.info("Received Maim_Message [ID: {} Platform: {}]: {}", messageId, platform, displayMessage);
                            broadcastMessage("§d[XiaoMai] §f" + displayMessage);
                        }
                    } catch (Exception e) {
                        LOGGER.error("Failed to parse Maim_Message from {}: {}", wsUrl, e.getMessage());
                        // 如果不是标准格式，尝试直接显示原始消息
                        try {
                            // 尝试解析为简单消息格式
                            Map<String, Object> simpleMessage = GSON.fromJson(message, Map.class);
                            if (simpleMessage.containsKey("content")) {
                                broadcastMessage("§d[XiaoMai] §f" + simpleMessage.get("content"));
                            } else {
                                broadcastMessage("§d[XiaoMai] §f" + message);
                            }
                        } catch (Exception ex) {
                            broadcastMessage("§d[XiaoMai] §f" + message);
                        }
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    isConnectedToMaibot = false;
                    LOGGER.info("Connection to MaiBot closed: {} (code: {})", reason, code);

                    if (ServerLifecycleHooks.getCurrentServer() != null) {
                        ServerLifecycleHooks.getCurrentServer().execute(() -> {
                            broadcastMessage("§cMaiBot connection closed: " + reason);
                            removeXiaoMaiEntity();
                        });
                    }
                }

                @Override
                public void onError(Exception ex) {
                    LOGGER.error("WebSocket error for {}: {}", wsUrl, ex.getMessage());

                    if (ServerLifecycleHooks.getCurrentServer() != null) {
                        ServerLifecycleHooks.getCurrentServer().execute(() -> {
                            broadcastMessage("§cWebSocket error: " + ex.getMessage());
                        });
                    }
                }
            };

            source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("Connecting to MaiBot at: " + wsUrl), false);
            maibotClient.connect();

        } catch (URISyntaxException e) {
            LOGGER.error("URI syntax error for {}: {}", wsUrl, e.getMessage());
            source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("Invalid WebSocket URL format: " + e.getMessage()), false);
        } catch (Exception e) {
            LOGGER.error("Connection failed to {}: {}", wsUrl, e.getMessage());
            source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("Connection failed: " + e.getMessage()), false);
        }
    }

    private void setWebSocketServer(net.minecraft.commands.CommandSourceStack source, String wsUrl) {
        try {
            // 验证 URL 格式
            if (!wsUrl.startsWith("ws://") && !wsUrl.startsWith("wss://")) {
                source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("Invalid WebSocket URL. Must start with ws:// or wss://"), false);
                return;
            }

            // 如果当前已连接，先断开
            if (isConnectedToMaibot) {
                disconnectFromMaibot(source);
            }

            currentWebSocketServer = wsUrl;
            source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("WebSocket server set to: " + wsUrl), false);
            source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("Use /maibot connect to connect to the new server"), false);

        } catch (Exception e) {
            source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("Failed to set WebSocket server: " + e.getMessage()), false);
        }
    }

    private void showServerInfo(net.minecraft.commands.CommandSourceStack source) {
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("=== MaiBot Server Info ==="), false);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("Current WebSocket Server: " + currentWebSocketServer), false);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("Connection Status: " + (isConnectedToMaibot ? "Connected" : "Not connected")), false);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("Default Server: " + DEFAULT_WS_SERVER), false);
    }

    private void sendChatMessage(net.minecraft.commands.CommandSourceStack source, String message) {
        if (!isConnectedToMaibot) {
            source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("Not connected to MaiBot. Use /maibot connect first."), false);
            return;
        }

        if (message == null || message.trim().isEmpty()) {
            source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("Message cannot be empty"), false);
            return;
        }

        try {
            // 获取发送者信息（如果是玩家）
            String senderName = "Console";
            if (source.getEntity() instanceof Player) {
                Player player = (Player) source.getEntity();
                senderName = player.getName().getString();
            }

            // 创建完整的消息 - 包含玩家名称
            String fullMessage = String.format("[%s] %s", senderName, message);

            // 发送 MaimMessage 格式的消息
            Map<String, Object> chatMessage = createMaimMessage(fullMessage, "chat", senderName);
            sendToMaiBot(GSON.toJson(chatMessage));

            source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("Message sent to MaiBot: " + message), false);
            LOGGER.info("Player {} sent message to MaiBot: {}", senderName, message);

        } catch (Exception e) {
            LOGGER.error("Failed to send chat message: {}", e.getMessage());
            source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("Failed to send message: " + e.getMessage()), false);
        }
    }

    private void sendQuestionMessage(net.minecraft.commands.CommandSourceStack source, String question) {
        if (!isConnectedToMaibot) {
            source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("Not connected to MaiBot. Use /maibot connect first."), false);
            return;
        }

        try {
            // 获取发送者信息
            String senderName = "Console";
            if (source.getEntity() instanceof Player) {
                Player player = (Player) source.getEntity();
                senderName = player.getName().getString();
            }

            // 创建问题格式的消息 - 更容易触发回复
            String fullMessage = String.format("[%s] 提问：%s", senderName, question);

            Map<String, Object> questionMessage = createMaimMessage(fullMessage, "question", senderName);
            sendToMaiBot(GSON.toJson(questionMessage));

            source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("Question sent to MaiBot: " + question), false);

        } catch (Exception e) {
            LOGGER.error("Failed to send question: {}", e.getMessage());
            source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("Failed to send question: " + e.getMessage()), false);
        }
    }

    /**
     * 创建符合 Maim_Message 标准的消息，包含用户信息
     */
    private Map<String, Object> createMaimMessage(String textContent, String messageType, String senderName) {
        String messageId = generateMessageId();
        double timestamp = System.currentTimeMillis() / 1000.0;

        // 创建 Seg 对象
        Map<String, Object> seg = new HashMap<>();
        seg.put("type", "text");
        seg.put("data", textContent);

        // 创建 UserInfo - 使用实际玩家信息
        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("platform", "minecraft");
        userInfo.put("user_id", "minecraft_player_" + senderName);
        userInfo.put("user_nickname", senderName);
        userInfo.put("user_cardname", senderName);

        // 创建 FormatInfo - 明确接受格式
        Map<String, Object> formatInfo = new HashMap<>();
        formatInfo.put("content_format", Arrays.asList("text"));
        formatInfo.put("accept_format", Arrays.asList("text", "image", "voice"));

        // 创建 Additional Config - 增加回复概率
        Map<String, Object> additionalConfig = new HashMap<>();
        additionalConfig.put("maimcore_reply_probability_gain", 0.5);
        additionalConfig.put("allow_tts", false);
        additionalConfig.put("conversation_context", "minecraft_chat");

        // 创建 BaseMessageInfo
        Map<String, Object> messageInfo = new HashMap<>();
        messageInfo.put("platform", "minecraft");
        messageInfo.put("message_id", messageId);
        messageInfo.put("time", timestamp);
        messageInfo.put("user_info", userInfo);
        messageInfo.put("format_info", formatInfo);
        messageInfo.put("additional_config", additionalConfig);
        // group_info 设为 null，因为这是私聊消息

        // 创建完整的 MessageBase
        Map<String, Object> messageBase = new HashMap<>();
        messageBase.put("message_info", messageInfo);
        messageBase.put("message_segment", seg);
        messageBase.put("raw_message", textContent);

        return messageBase;
    }

    /**
     * 从 Seg 对象中提取文本内容
     */
    private String extractTextFromSeg(String segType, Object segData) {
        if ("text".equals(segType)) {
            return segData.toString();
        } else if ("seglist".equals(segType)) {
            // 处理 seglist 类型，递归提取文本
            List<Map<String, Object>> segList = (List<Map<String, Object>>) segData;
            StringBuilder textBuilder = new StringBuilder();
            for (Map<String, Object> seg : segList) {
                String subType = (String) seg.get("type");
                Object subData = seg.get("data");
                String subText = extractTextFromSeg(subType, subData);
                if (subText != null) {
                    textBuilder.append(subText);
                }
            }
            return textBuilder.toString();
        } else {
            LOGGER.warn("Unsupported segment type: {}", segType);
            return null;
        }
    }

    /**
     * 生成唯一的消息ID
     * 格式: minecraft_{timestamp}_{counter}
     */
    private String generateMessageId() {
        long timestamp = System.currentTimeMillis();
        long counter = MESSAGE_ID_COUNTER.getAndIncrement();
        return String.format("minecraft_%d_%d", timestamp, counter);
    }

    private void sendToMaiBot(String message) {
        if (maibotClient != null && isConnectedToMaibot) {
            try {
                maibotClient.send(message);
                LOGGER.info("Sent to MaiBot Adapter: " + message);

                // 在游戏内显示发送确认
                if (ServerLifecycleHooks.getCurrentServer() != null) {
                    ServerLifecycleHooks.getCurrentServer().execute(() -> {
                        // 不广播发送确认，避免 spam
                        // broadcastMessage("§a消息已发送到 MaiBot");
                    });
                }
            } catch (Exception e) {
                LOGGER.error("Failed to send message to MaiBot: " + e.getMessage());

                // 在游戏内显示错误
                if (ServerLifecycleHooks.getCurrentServer() != null) {
                    ServerLifecycleHooks.getCurrentServer().execute(() -> {
                        broadcastMessage("§c发送消息失败: " + e.getMessage());
                    });
                }
            }
        } else {
            LOGGER.warn("Not connected to MaiBot, cannot send message");

            // 在游戏内显示未连接警告
            if (ServerLifecycleHooks.getCurrentServer() != null) {
                ServerLifecycleHooks.getCurrentServer().execute(() -> {
                    broadcastMessage("§c未连接到 MaiBot，请先使用 /maibot connect");
                });
            }
        }
    }

    private void disconnectFromMaibot(net.minecraft.commands.CommandSourceStack source) {
        if (maibotClient != null && isConnectedToMaibot) {
            // 发送断开连接消息
            Map<String, Object> disconnectMessage = createMaimMessage(
                    "Minecraft server disconnecting",
                    "system",
                    "Minecraft Server"
            );
            sendToMaiBot(GSON.toJson(disconnectMessage));

            maibotClient.close();
            source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("Disconnected from MaiBot"), false);
            removeXiaoMaiEntity();
        } else {
            source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("Not connected to MaiBot"), false);
        }
    }

    private void disconnectFromMaibot() {
        if (maibotClient != null && isConnectedToMaibot) {
            maibotClient.close();
        }
    }

    private void spawnXiaoMaiEntity(net.minecraft.commands.CommandSourceStack source) {
        if (ServerLifecycleHooks.getCurrentServer() == null) {
            source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("Server not ready, cannot spawn XiaoMai"), false);
            return;
        }

        if (xiaoMaiEntity != null && xiaoMaiEntity.isAlive()) {
            source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("XiaoMai already exists in the world!"), false);
            return;
        }

        spawnXiaoMaiEntity();
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("XiaoMai entity spawned!"), false);
    }

    private void spawnXiaoMaiEntity() {
        if (ServerLifecycleHooks.getCurrentServer() == null) return;

        Player firstPlayer = null;
        if (!ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayers().isEmpty()) {
            firstPlayer = ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayers().get(0);
        }

        net.minecraft.world.level.Level level = ServerLifecycleHooks.getCurrentServer().overworld();
        net.minecraft.world.phys.Vec3 spawnPos;

        if (firstPlayer != null) {
            spawnPos = firstPlayer.position().add(2, 0, 2);
        } else {
            net.minecraft.core.BlockPos spawnBlockPos = level.getSharedSpawnPos();
            spawnPos = new net.minecraft.world.phys.Vec3(
                    spawnBlockPos.getX() + 0.5,
                    spawnBlockPos.getY(),
                    spawnBlockPos.getZ() + 0.5
            );
        }

        // 使用自定义实体
        xiaoMaiEntity = new XiaoMaiEntity(XIAO_MAI.get(), level);
        xiaoMaiEntity.moveTo(spawnPos.x(), spawnPos.y(), spawnPos.z(), 0, 0);
        xiaoMaiEntity.setCustomName(net.minecraft.network.chat.Component.literal("XiaoMai"));
        xiaoMaiEntity.setCustomNameVisible(true);
        xiaoMaiEntity.setInvulnerable(true);

        level.addFreshEntity(xiaoMaiEntity);

        LOGGER.info("XiaoMai entity spawned at position {}", spawnPos);
    }

    private void removeXiaoMaiEntity(net.minecraft.commands.CommandSourceStack source) {
        if (xiaoMaiEntity != null) {
            xiaoMaiEntity.discard();
            xiaoMaiEntity = null;
            followingPlayerId = null;
            source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("XiaoMai entity removed"), false);
        } else {
            source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("XiaoMai entity does not exist"), false);
        }
    }

    private void removeXiaoMaiEntity() {
        if (xiaoMaiEntity != null) {
            xiaoMaiEntity.discard();
            xiaoMaiEntity = null;
            followingPlayerId = null;
        }
    }

    private void setFollowingPlayer(net.minecraft.commands.CommandSourceStack source, Player player) {
        if (xiaoMaiEntity == null || !xiaoMaiEntity.isAlive()) {
            source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("XiaoMai entity does not exist, please use /maibot connect first"), false);
            return;
        }

        followingPlayerId = player.getUUID();
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("XiaoMai will now follow player " + player.getName().getString()), false);
        player.displayClientMessage(net.minecraft.network.chat.Component.literal("XiaoMai started following you!"), false);
    }

    private void showStatus(net.minecraft.commands.CommandSourceStack source) {
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("=== MaiBot Status ==="), false);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("Connection: " + (isConnectedToMaibot ? "Connected" : "Not connected")), false);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("WebSocket Server: " + currentWebSocketServer), false);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("XiaoMai Entity: " + (xiaoMaiEntity != null && xiaoMaiEntity.isAlive() ? "Exists" : "Does not exist")), false);

        if (followingPlayerId != null) {
            Player player = ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayer(followingPlayerId);
            if (player != null) {
                source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("Following: " + player.getName().getString()), false);
            } else {
                source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("Following: Player offline"), false);
            }
        } else {
            source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("Following: None"), false);
        }
    }

    private void sendTestMessage(net.minecraft.commands.CommandSourceStack source) {
        if (!isConnectedToMaibot) {
            source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("Not connected to MaiBot. Use /maibot connect first."), false);
            return;
        }

        Map<String, Object> testMessage = createMaimMessage(
                "This is a test message from Minecraft server",
                "test",
                "Minecraft Server"
        );
        sendToMaiBot(GSON.toJson(testMessage));
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("Test message sent to MaiBot"), false);
    }

    private void sendPingMessage(net.minecraft.commands.CommandSourceStack source) {
        if (!isConnectedToMaibot) {
            source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("Not connected to MaiBot. Use /maibot connect first."), false);
            return;
        }

        // 发送简单的测试消息
        Map<String, Object> pingMessage = new HashMap<>();
        pingMessage.put("type", "ping");
        pingMessage.put("content", "Ping from Minecraft server");
        pingMessage.put("timestamp", System.currentTimeMillis());

        sendToMaiBot(GSON.toJson(pingMessage));
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("Ping message sent to MaiBot"), false);
    }

    private void showQuickHelp(net.minecraft.commands.CommandSourceStack source) {
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("=== MaiBot Mod 快速帮助 ==="), false);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("使用 /maibot help 查看完整帮助"), false);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("基础命令:"), false);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("  /maibot connect [ws_url] - 连接到 MaiBot"), false);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("  /maibot send <消息> - 发送消息到 MaiBot"), false);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("  /maibot ask <问题> - 提问（更容易获得回复）"), false);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("  /maibot status - 查看状态"), false);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("  /maibot help - 完整帮助"), false);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("默认服务器: " + DEFAULT_WS_SERVER), false);
    }

    private void showDetailedHelp(net.minecraft.commands.CommandSourceStack source) {
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("=== MaiBot Mod 完整帮助 ==="), false);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("连接命令:"), false);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("  /maibot connect - 使用默认服务器连接"), false);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("  /maibot connect <ws://ip:port> - 指定服务器连接"), false);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("  /maibot disconnect - 断开连接"), false);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("  /maibot set_server <ws_url> - 设置服务器地址"), false);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("  /maibot server_info - 显示服务器信息"), false);

        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("消息命令:"), false);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("  /maibot send <消息> - 发送消息到 MaiBot"), false);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("  /maibot ask <问题> - 提问（更容易获得回复）"), false);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("  /maibot test - 发送测试消息"), false);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("  /maibot ping - 发送 ping 消息"), false);

        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("实体命令:"), false);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("  /maibot spawn - 生成 XiaoMai 实体"), false);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("  /maibot remove - 移除 XiaoMai 实体"), false);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("  /maigs - 让 XiaoMai 跟随你"), false);

        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("信息命令:"), false);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("  /maibot status - 查看完整状态"), false);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("  /maibot help - 显示此帮助"), false);

        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("消息技巧:"), false);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("  使用 /maibot ask 提问更容易获得回复"), false);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("  包含具体问题或请求"), false);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("  避免过于简单的问候"), false);

        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("使用示例:"), false);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("  /maibot connect ws://192.168.1.100:8765"), false);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("  /maibot ask 麦麦，你能介绍一下自己吗？"), false);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("  /maibot send 你好，我是 Minecraft 玩家"), false);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("  /maibot set_server ws://localhost:8888"), false);

        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("默认 WebSocket 服务器: " + DEFAULT_WS_SERVER), false);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("连接后 XiaoMai 会以自定义模型出现在世界中！"), false);
    }

    private void broadcastMessage(String message) {
        if (ServerLifecycleHooks.getCurrentServer() != null) {
            ServerLifecycleHooks.getCurrentServer().getPlayerList().broadcastSystemMessage(
                    net.minecraft.network.chat.Component.literal(message), false
            );
        }
    }
}