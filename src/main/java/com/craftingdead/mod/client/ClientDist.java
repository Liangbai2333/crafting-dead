package com.craftingdead.mod.client;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFW;
import com.craftingdead.mod.CommonConfig;
import com.craftingdead.mod.CraftingDead;
import com.craftingdead.mod.IModDist;
import com.craftingdead.mod.capability.ModCapabilities;
import com.craftingdead.mod.capability.SerializableProvider;
import com.craftingdead.mod.capability.player.ClientPlayer;
import com.craftingdead.mod.capability.player.DefaultPlayer;
import com.craftingdead.mod.capability.triggerable.GunController;
import com.craftingdead.mod.client.DiscordPresence.GameState;
import com.craftingdead.mod.client.animation.AnimationManager;
import com.craftingdead.mod.client.animation.GunAnimation;
import com.craftingdead.mod.client.crosshair.CrosshairManager;
import com.craftingdead.mod.client.gui.GuiIngame;
import com.craftingdead.mod.client.renderer.entity.AdvancedZombieRenderer;
import com.craftingdead.mod.client.renderer.entity.CorpseRenderer;
import com.craftingdead.mod.entity.CorpseEntity;
import com.craftingdead.mod.entity.monster.AdvancedZombieEntity;
import com.craftingdead.mod.event.GunEvent;
import com.craftingdead.mod.item.GunItem;
import com.craftingdead.mod.masterserver.net.protocol.handshake.message.HandshakeMessage;
import com.craftingdead.mod.masterserver.net.protocol.playerlogin.PlayerLoginProtocol;
import com.craftingdead.mod.masterserver.net.protocol.playerlogin.PlayerLoginSession;
import com.craftingdead.mod.masterserver.net.protocol.playerlogin.message.PlayerLoginMessage;
import com.craftingdead.network.pipeline.NetworkManager;
import com.mojang.authlib.yggdrasil.YggdrasilMinecraftSessionService;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.AbstractClientPlayerEntity;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.renderer.entity.model.BipedModel;
import net.minecraft.client.renderer.entity.model.BipedModel.ArmPose;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.resources.IReloadableResourceManager;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Session;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.StartupMessageManager;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.client.registry.RenderingRegistry;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

public class ClientDist implements IModDist {

  public static final KeyBinding RELOAD =
      new KeyBinding("key.reload", GLFW.GLFW_KEY_R, "key.categories.gameplay");
  public static final KeyBinding TOGGLE_FIRE_MODE =
      new KeyBinding("key.toggle_fire_mode", GLFW.GLFW_KEY_F, "key.categories.gameplay");

  private static final Logger logger = LogManager.getLogger();

  private static final Minecraft minecraft = Minecraft.getInstance();

  @Getter
  private CrosshairManager crosshairManager = new CrosshairManager();

  @Getter
  private AnimationManager animationManager = new AnimationManager();

  private RecoilHelper recoilHelper = new RecoilHelper();

  private GuiIngame guiIngame;

  public ClientDist() {
    FMLJavaModLoadingContext.get().getModEventBus().register(this);
    MinecraftForge.EVENT_BUS.register(this);

    ((IReloadableResourceManager) minecraft.getResourceManager())
        .addReloadListener(this.crosshairManager);

    this.guiIngame = new GuiIngame(minecraft, this, CrosshairManager.DEFAULT_CROSSHAIR);
  }

  @Override
  public boolean isUsingNativeTransport() {
    return minecraft.gameSettings.isUsingNativeTransport();
  }

  @Override
  public void handleConnect(NetworkManager networkManager) {
    networkManager.sendMessage(new HandshakeMessage(HandshakeMessage.PLAYER_LOGIN));
    networkManager.setProtocol(new PlayerLoginSession(networkManager),
        PlayerLoginProtocol.INSTANCE);

    Session session = minecraft.getSession();
    UUID id = session.getProfile().getId();
    String username = session.getUsername();
    String clientToken = ((YggdrasilMinecraftSessionService) minecraft.getSessionService())
        .getAuthenticationService().getClientToken();
    String accessToken = session.getToken();
    networkManager.sendMessage(
        new PlayerLoginMessage(id, username, clientToken, accessToken, CraftingDead.VERSION));
  }

  public LazyOptional<ClientPlayer> getPlayer() {
    return minecraft.player != null
        ? minecraft.player.getCapability(ModCapabilities.PLAYER, null).cast()
        : LazyOptional.empty();
  }

  // ================================================================================
  // Mod Events
  // ================================================================================

  @SubscribeEvent
  public void handleClientSetup(FMLClientSetupEvent event) {
    ClientRegistry.registerKeyBinding(TOGGLE_FIRE_MODE);
    ClientRegistry.registerKeyBinding(RELOAD);

    RenderingRegistry.registerEntityRenderingHandler(CorpseEntity.class, CorpseRenderer::new);
    RenderingRegistry.registerEntityRenderingHandler(AdvancedZombieEntity.class,
        AdvancedZombieRenderer::new);

    // GLFW code needs to run on main thread
    minecraft.enqueue(() -> {
      if (CommonConfig.clientConfig.applyBranding.get()) {
        StartupMessageManager.addModMessage("Applying branding");
        GLFW.glfwSetWindowTitle(minecraft.mainWindow.getHandle(),
            String.format("%s %s", CraftingDead.DISPLAY_NAME, CraftingDead.VERSION));
        try {
          InputStream inputstream = minecraft.getResourceManager()
              .getResource(
                  new ResourceLocation(CraftingDead.ID, "textures/gui/icons/icon_16x16.png"))
              .getInputStream();
          InputStream inputstream1 = minecraft.getResourceManager()
              .getResource(
                  new ResourceLocation(CraftingDead.ID, "textures/gui/icons/icon_32x32.png"))
              .getInputStream();
          minecraft.mainWindow.setWindowIcon(inputstream, inputstream1);
        } catch (IOException e) {
          logger.error("Couldn't set icon", e);
        }
      }
    });
  }

  @SubscribeEvent
  public void handleLoadComplete(FMLLoadCompleteEvent event) {
    if (CommonConfig.clientConfig.enableDiscordRpc.get()) {
      StartupMessageManager.addModMessage("Loading Discord integration");
      DiscordPresence.initialize("475405055302828034");
    }
    DiscordPresence.updateState(GameState.IDLE, this);
  }

  // ================================================================================
  // Forge Events
  // ================================================================================

  @SubscribeEvent
  public void handleClientTick(TickEvent.ClientTickEvent event) {
    switch (event.phase) {
      case END:
        CraftingDead.getInstance().tickConnection();
        break;
      default:
        break;
    }
  }

  @SubscribeEvent
  public void handleRenderTick(TickEvent.RenderTickEvent event) {
    switch (event.phase) {
      case START:
        if (minecraft.player != null) {
          this.recoilHelper.update(minecraft.player);
        }
        break;
      default:
        break;
    }
  }

  @SubscribeEvent
  public void handleMouseInput(InputEvent.MouseInputEvent event) {
    if (minecraft.getConnection() != null && minecraft.currentScreen == null) {
      if (event.getButton() == minecraft.gameSettings.keyBindAttack.getKey().getKeyCode()) {
        boolean press = event.getAction() == GLFW.GLFW_PRESS;
        this.getPlayer().ifPresent((player) -> {
          player.setTriggerPressed(press);
        });
      }
    }
  }

  @SubscribeEvent
  public void handleAttachCapabilities(AttachCapabilitiesEvent<Entity> event) {
    if (event.getObject() instanceof ClientPlayerEntity) {
      event.addCapability(new ResourceLocation(CraftingDead.ID, "player"),
          new SerializableProvider<>(new ClientPlayer((ClientPlayerEntity) event.getObject()),
              ModCapabilities.PLAYER));
    } else if (event.getObject() instanceof AbstractClientPlayerEntity) {
      event.addCapability(new ResourceLocation(CraftingDead.ID, "player"),
          new SerializableProvider<>(
              new DefaultPlayer<>((AbstractClientPlayerEntity) event.getObject()),
              ModCapabilities.PLAYER));
    }
  }

  @SubscribeEvent
  public void handleEntityJoinWorld(EntityJoinWorldEvent event) {
    if (minecraft.isIntegratedServerRunning()) {
      DiscordPresence.updateState(GameState.SINGLEPLAYER, this);
    } else {
      ServerData serverData = minecraft.getCurrentServerData();
      DiscordPresence.updateState(serverData.isOnLAN() ? GameState.LAN : GameState.MULTIPLAYER,
          this);
    }

  }

  @SubscribeEvent
  public void handleGuiOpen(GuiOpenEvent event) {
    DiscordPresence.updateState(GameState.IDLE, this);
  }

  @SubscribeEvent
  public void handleRenderLiving(RenderLivingEvent.Pre<?, BipedModel<?>> event) {
    // We don't use RenderPlayerEvent.Pre as it gets called too early resulting in
    // changes we make to the arm pose being overwritten
    ItemStack stack = event.getEntity().getHeldItemMainhand();
    // isAssignableFrom is faster than instanceof
    if (GunItem.class.isAssignableFrom(stack.getItem().getClass())) {
      BipedModel<?> model = event.getRenderer().getEntityModel();
      switch (event.getEntity().getPrimaryHand()) {
        case LEFT:
          model.leftArmPose = ArmPose.BOW_AND_ARROW;
          break;
        case RIGHT:
          model.rightArmPose = ArmPose.BOW_AND_ARROW;
          break;
        default:
          break;
      }
    }
  }

  @SubscribeEvent
  public void handleRenderGameOverlayPre(RenderGameOverlayEvent.Pre event) {
    switch (event.getType()) {
      case ALL:
        this.guiIngame.renderGameOverlay(event.getPartialTicks());
        break;
      case CROSSHAIRS:
        this.getPlayer().ifPresent((player) -> {
          player.getEntity().getHeldItemMainhand().getCapability(ModCapabilities.AIMABLE)
              .ifPresent((aimable) -> {
                event.setCanceled(true);
                this.guiIngame.renderCrosshairs(aimable.getAccuracy(), event.getPartialTicks());
              });
        });
        break;
      default:
        break;
    }
  }

  @SubscribeEvent
  public void handleModelRegistry(ModelRegistryEvent event) {
    // ModelRegistry.registerModels(client);
  }

  @SubscribeEvent
  public void handleGunShootEventPre(GunEvent.ShootEvent.Pre event) {
    // This is event can be called on both the client thread and server thread so
    // check we are on the client first
    if (event.getEntity().world.isRemote()) {
      GunController gunController = event.getController();
      // Only jolt camera if the event is for our own player
      if (event.getEntity() == minecraft.player) {
        this.recoilHelper.jolt(gunController.getAccuracy());
      }
      Supplier<GunAnimation> animation =
          gunController.getItem().getAnimations().get(GunAnimation.Type.SHOOT);
      if (animation != null && animation.get() != null) {
        AnimationManager animationManager = this.animationManager;
        animationManager.clear(event.getItemStack());
        animationManager.setNextGunAnimation(event.getItemStack(), animation.get());
      }
    }
  }
}