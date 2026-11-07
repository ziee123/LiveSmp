import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.*;

public class LiveSmp extends JavaPlugin implements Listener, CommandExecutor {

    private static final int START_LIVES = 3;
    private static final long BAN_MS = 15L * 24 * 60 * 60 * 1000; // 15 hari

    // Hanya mob ini yang mengurangi nyawa
    private static final Set<EntityType> BOSSES = EnumSet.of(
            EntityType.WARDEN,
            EntityType.WITHER,
            EntityType.ENDER_DRAGON
    );

    private NamespacedKey eyeKey;
    private NamespacedKey ownerKey;
    private File dataFile;
    private YamlConfiguration data;
    private final Random random = new Random();

    // ===================== SETUP =====================

    @Override
    public void onEnable() {
        eyeKey = new NamespacedKey(this, "revival_eye");
        ownerKey = new NamespacedKey(this, "owner");

        getDataFolder().mkdirs();
        dataFile = new File(getDataFolder(), "data.yml");
        data = dataFile.exists() ? YamlConfiguration.loadConfiguration(dataFile) : new YamlConfiguration();

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("livesmp").setExecutor(this);
        registerRecipe();
    }

    @Override
    public void onDisable() {
        save();
    }

    private void registerRecipe() {
        // Enchanted Golden Apple = G, Ender Eye = E, Totem of Undying = T
        // E G E
        // G T G
        // E G E
        ShapedRecipe recipe = new ShapedRecipe(new NamespacedKey(this, "revival_eye_recipe"), createEye());
        recipe.shape("EGE", "GTG", "EGE");
        recipe.setIngredient('E', Material.ENDER_EYE);
        recipe.setIngredient('G', Material.ENCHANTED_GOLDEN_APPLE);
        recipe.setIngredient('T', Material.TOTEM_OF_UNDYING);
        getServer().addRecipe(recipe);
    }

    // ===================== REVIVAL EYE =====================

    private ItemStack createEye() {
        ItemStack item = new ItemStack(Material.ENDER_EYE);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§bRevival Eye");
        meta.setLore(List.of("§7Bawa barang milik pemain yang", "§7ingin direvive, lalu klik kanan"));
        meta.getPersistentDataContainer().set(eyeKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isEye(ItemStack item) {
        if (item == null || item.getType() != Material.ENDER_EYE) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(eyeKey, PersistentDataType.BYTE);
    }

    @EventHandler
    public void onRightClick(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (!isEye(e.getItem())) return;

        e.setCancelled(true);
        Player reviver = e.getPlayer();

        UUID ownerId = findOwnerInInventory(reviver);
        if (ownerId == null) {
            reviver.sendMessage("§cBawa barang milik pemain yang mau direvive di inventory kamu!");
            return;
        }

        // Pemilik barang sudah mati/banned -> revive dia.
        // Pemilik barang masih hidup -> revive pemain banned secara acak.
        UUID targetId = isDead(ownerId) ? ownerId : randomDead();
        if (targetId == null) {
            reviver.sendMessage("§cTidak ada pemain yang sedang banned untuk direvive.");
            return;
        }

        if (revive(targetId, reviver.getName())) {
            consumeEye(reviver);
        }
    }

    // Cari pemilik barang di inventory. Prioritaskan pemilik yang sudah mati.
    private UUID findOwnerInInventory(Player p) {
        UUID fallback = null;
        for (ItemStack item : p.getInventory().getContents()) {
            UUID owner = getOwner(item);
            if (owner == null) continue;
            if (isDead(owner)) return owner;
            if (fallback == null) fallback = owner;
        }
        return fallback;
    }

    private boolean consumeEye(Player p) {
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (isEye(item)) {
                if (item.getAmount() > 1) {
                    item.setAmount(item.getAmount() - 1);
                    p.getInventory().setItem(i, item);
                } else {
                    p.getInventory().setItem(i, null);
                }
                return true;
            }
        }
        return false;
    }

    private boolean revive(UUID id, String by) {
        String name = data.getString("dead." + id);
        if (name == null) return false;

        Bukkit.getBanList(BanList.Type.NAME).pardon(name);
        data.set("dead." + id, null);
        setLives(id, START_LIVES);
        Bukkit.broadcastMessage("§a" + by + " telah merevive §f" + name + "§a!");
        return true;
    }

    private UUID randomDead() {
        var section = data.getConfigurationSection("dead");
        if (section == null) return null;
        List<String> keys = new ArrayList<>(section.getKeys(false));
        if (keys.isEmpty()) return null;
        return UUID.fromString(keys.get(random.nextInt(keys.size())));
    }

    // ===================== TAG BARANG =====================

    // Tandai barang dengan UUID pemiliknya. Mengembalikan salinan yang sudah ditandai.
    private ItemStack tagOwner(ItemStack item, UUID id, String name) {
        if (item == null || item.getType().isAir() || isEye(item)) return item;
        ItemStack copy = item.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta == null) return item;

        meta.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, id.toString());
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.removeIf(l -> l.startsWith("§8Milik: "));
        lore.add("§8Milik: " + name);
        meta.setLore(lore);

        copy.setItemMeta(meta);
        return copy;
    }

    private UUID getOwner(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        String value = meta.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        return value == null ? null : UUID.fromString(value);
    }

    // ===================== NYAWA & KEMATIAN =====================

    private int getLives(UUID id) {
        return data.getInt("lives." + id, START_LIVES);
    }

    private void setLives(UUID id, int lives) {
        data.set("lives." + id, lives);
        save();
    }

    private boolean isDead(UUID id) {
        return data.contains("dead." + id);
    }

    private void markDead(UUID id, String name) {
        data.set("dead." + id, name);
        save();
    }

    private boolean isBossKill(Player p) {
        EntityDamageEvent last = p.getLastDamageCause();
        if (last == null) return false;

        if (last.getCause() == EntityDamageEvent.DamageCause.DRAGON_BREATH) return true;

        if (last instanceof EntityDamageByEntityEvent byEntity) {
            Entity damager = byEntity.getDamager();
            // Proyektil (skull wither, fireball dragon, dll.) -> ambil penembaknya
            if (damager instanceof Projectile proj && proj.getShooter() instanceof Entity shooter) {
                damager = shooter;
            }
            if (BOSSES.contains(damager.getType())) return true;
            if (damager.getType() == EntityType.DRAGON_FIREBALL || damager.getType() == EntityType.WITHER_SKULL) {
                return true;
            }
        }
        return false;
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        UUID id = p.getUniqueId();
        String name = p.getName();

        // Semua barang pemain ditandai sebagai miliknya
        List<ItemStack> drops = e.getDrops();
        for (int i = 0; i < drops.size(); i++) {
            drops.set(i, tagOwner(drops.get(i), id, name));
        }
        if (e.getKeepInventory()) {
            PlayerInventory inv = p.getInventory();
            ItemStack[] contents = inv.getContents();
            for (int i = 0; i < contents.length; i++) {
                contents[i] = tagOwner(contents[i], id, name);
            }
            inv.setContents(contents);
        }

        if (!isBossKill(p)) {
            p.sendMessage("§7Mati biasa, nyawa kamu tetap: §a" + getLives(id));
            return;
        }

        int lives = Math.max(0, getLives(id) - 1);
        setLives(id, lives);

        if (lives > 0) {
            p.sendMessage("§cKamu kehilangan 1 nyawa karena boss! Sisa nyawa: §e" + lives);
            return;
        }

        // Nyawa habis -> ban 15 hari
        markDead(id, name);
        Date expiry = new Date(System.currentTimeMillis() + BAN_MS);
        Bukkit.getBanList(BanList.Type.NAME).addBan(name, "Nyawa habis. Ban 15 hari.", expiry, "LiveSmp");

        Bukkit.getScheduler().runTask(this, () ->
                p.kickPlayer("§cNyawa kamu habis!\n§7Kamu di-ban selama 15 hari.\n§7Minta temanmu revive pakai Revival Eye."));
    }

    // ===================== COMMAND ADMIN =====================

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("livesmp.admin")) {
            sender.sendMessage("§cKamu tidak punya izin untuk perintah ini.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("§eGunakan: /livesmp <check|revive|remove> <nama>");
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[1]);
        if (target == null) {
            sender.sendMessage("§cPemain tidak dikenal.");
            return true;
        }
        UUID id = target.getUniqueId();
        String name = target.getName() != null ? target.getName() : args[1];

        switch (args[0].toLowerCase()) {
            case "check" -> {
                String status = isDead(id) ? "§cBanned" : "§aHidup";
                sender.sendMessage("§e" + name + " §7| Nyawa: §a" + getLives(id) + " §7| Status: " + status);
            }
            case "revive" -> {
                if (!isDead(id)) {
                    sender.sendMessage("§c" + name + " tidak sedang banned.");
                } else {
                    revive(id, "Admin");
                    sender.sendMessage("§aBerhasil merevive " + name + ".");
                }
            }
            case "remove" -> {
                if (isDead(id)) {
                    sender.sendMessage("§c" + name + " sudah banned, nyawanya tidak bisa dikurangi lagi.");
                } else {
                    int lives = Math.max(0, getLives(id) - 1);
                    setLives(id, lives);
                    if (lives > 0) {
                        sender.sendMessage("§aNyawa " + name + " dikurangi 1. Sisa: §e" + lives);
                    } else {
                        banDead(id, name);
                        sender.sendMessage("§c" + name + " kehabisan nyawa dan di-ban 15 hari.");
                    }
                }
            }
            default -> sender.sendMessage("§eGunakan: /livesmp <check|revive|remove> <nama>");
        }
        return true;
    }

    // Tandai pemain mati, ban 15 hari, dan kick jika sedang online
    private void banDead(UUID id, String name) {
        markDead(id, name);
        Date expiry = new Date(System.currentTimeMillis() + BAN_MS);
        Bukkit.getBanList(BanList.Type.NAME).addBan(name, "Nyawa habis. Ban 15 hari.", expiry, "LiveSmp");

        Player online = Bukkit.getPlayer(id);
        if (online != null) {
            Bukkit.getScheduler().runTask(this, () ->
                    online.kickPlayer("§cNyawa kamu habis!\n§7Kamu di-ban selama 15 hari."));
        }
    }

    // ===================== UTIL =====================

    private void save() {
        try {
            data.save(dataFile);
        } catch (Exception ex) {
            getLogger().severe("Gagal menyimpan data.yml: " + ex.getMessage());
        }
    }
          }
