package com.foxsrv.domain;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class DomainCraft extends JavaPlugin implements Listener {

    // ====================================================
    // NBT KEYS
    // ====================================================
    private NamespacedKey domainNameKey;
    private NamespacedKey craftIdKey;
    private NamespacedKey guiCraftIdKey;
    private NamespacedKey isGuiItemKey;

    // ====================================================
    // DATA STORAGE
    // ====================================================
    private File dataFile;
    private Map<String, Domain> domains = new ConcurrentHashMap<>();
    private Map<String, BlockData> blockDataMap = new ConcurrentHashMap<>();
    private Map<UUID, PlayerEditSession> editSessions = new ConcurrentHashMap<>();
    private Map<UUID, PlayerCraftSession> craftSessions = new ConcurrentHashMap<>();

    // SLOT DEFINITIONS - 2x2 SQUARES
    private final int[] INGREDIENT_SLOTS = {10, 11, 19, 20}; // Left square
    private final int[] RESULT_SLOTS = {15, 16, 24, 25}; // Right square
    private final int DISPLAY_SLOT = 22; // Center

    // Pagination constants
    private final int ITEMS_PER_PAGE = 45; // slots 0-44
    private final int PREV_PAGE_SLOT = 48;
    private final int NEXT_PAGE_SLOT = 50;
    private final int CLOSE_SLOT = 49;
    private final int BACK_SLOT = 45;

    // ====================================================
    // ON ENABLE / DISABLE
    // ====================================================
    @Override
    public void onEnable() {
        getLogger().info("=== Starting DomainCraft v" + getDescription().getVersion() + " ===");
        try {
            domainNameKey = new NamespacedKey(this, "domain_name");
            craftIdKey = new NamespacedKey(this, "craft_id");
            guiCraftIdKey = new NamespacedKey(this, "gui_craft_id");
            isGuiItemKey = new NamespacedKey(this, "is_gui_item");

            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            dataFile = new File(getDataFolder(), "data.dat");

            loadData();

            getServer().getPluginManager().registerEvents(this, this);

            DomainCommand domainCommand = new DomainCommand();
            Objects.requireNonNull(getCommand("domain")).setExecutor(domainCommand);
            Objects.requireNonNull(getCommand("domain")).setTabCompleter(domainCommand);

            getLogger().info("=== DomainCraft v" + getDescription().getVersion() + " enabled successfully! ===");
            getLogger().info("Loaded " + domains.size() + " domains.");
        } catch (Exception e) {
            getLogger().severe("FATAL ERROR ENABLING PLUGIN: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        saveData();
        editSessions.clear();
        craftSessions.clear();
        getLogger().info("DomainCraft disabled.");
    }

    // ====================================================
    // DATA SERIALIZATION
    // ====================================================
    private void loadData() {
        if (dataFile.exists()) {
            try (BukkitObjectInputStream ois = new BukkitObjectInputStream(new FileInputStream(dataFile))) {
                int size = ois.readInt();
                for (int i = 0; i < size; i++) {
                    String name = (String) ois.readObject();
                    Domain domain = (Domain) ois.readObject();
                    domains.put(name, domain);
                    if (domain.getLocation() != null) {
                        blockDataMap.put(getBlockKey(domain.getLocation()), new BlockData(name, domain.getLocation()));
                    }
                }
            } catch (Exception e) {
                getLogger().severe("Failed to load data.dat: " + e.getMessage());
            }
        }
    }

    private void saveData() {
        try (BukkitObjectOutputStream oos = new BukkitObjectOutputStream(new FileOutputStream(dataFile))) {
            oos.writeInt(domains.size());
            for (Map.Entry<String, Domain> entry : domains.entrySet()) {
                oos.writeObject(entry.getKey());
                oos.writeObject(entry.getValue());
            }
        } catch (IOException e) {
            getLogger().severe("Failed to save data.dat: " + e.getMessage());
        }
    }

    private String getBlockKey(Location loc) {
        return loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    // ====================================================
    // DOMAIN MANAGEMENT
    // ====================================================
    private boolean createDomain(String name, Player player) {
        if (domains.containsKey(name)) return false;
        Block target = player.getTargetBlockExact(5);
        if (target == null || target.getType() == Material.AIR) return false;
        Domain domain = new Domain(name, target.getLocation(), player.getUniqueId());
        domains.put(name, domain);
        blockDataMap.put(getBlockKey(target.getLocation()), new BlockData(name, target.getLocation()));
        saveData();
        return true;
    }

    private boolean setDomainPermission(String name, String permission) {
        Domain domain = domains.get(name);
        if (domain == null) return false;
        domain.setRequiredPermission(permission);
        saveData();
        return true;
    }

    private boolean deleteDomain(String name) {
        Domain domain = domains.remove(name);
        if (domain != null) {
            blockDataMap.remove(getBlockKey(domain.getLocation()));
            saveData();
            return true;
        }
        return false;
    }

    private Domain getDomainFromBlock(Block block) {
        if (block == null || block.getType() == Material.AIR) return null;
        String key = getBlockKey(block.getLocation());
        BlockData data = blockDataMap.get(key);
        return data != null ? domains.get(data.domainName) : null;
    }

    private boolean hasPermission(Player player, Domain domain) {
        String perm = domain.getRequiredPermission();
        return perm == null || perm.isEmpty() || player.hasPermission(perm);
    }

    // ====================================================
    // CRAFT MANAGEMENT
    // ====================================================
    private void addCraft(String domainName, Craft craft) {
        Domain d = domains.get(domainName);
        if (d != null) {
            d.addCraft(craft);
            saveData();
        }
    }

    private void removeCraft(String domainName, String craftId) {
        Domain d = domains.get(domainName);
        if (d != null) {
            d.removeCraft(craftId);
            saveData();
        }
    }

    private void updateCraft(String domainName, Craft craft) {
        Domain d = domains.get(domainName);
        if (d != null) {
            d.updateCraft(craft);
            saveData();
        }
    }

    // ====================================================
    // CRAFT COST CALCULATION (for sorting)
    // ====================================================
    private int calculateCraftCost(Craft craft) {
        int total = 0;
        for (ItemStack ing : craft.getIngredients()) {
            if (ing != null && ing.getType() != Material.AIR) {
                total += ing.getAmount();
            }
        }
        return total;
    }

    // ====================================================
    // GUI HELPERS
    // ====================================================
    private ItemStack createGuiItem(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        if (lore.length > 0) meta.setLore(Arrays.asList(lore));
        meta.getPersistentDataContainer().set(isGuiItemKey, PersistentDataType.BOOLEAN, true);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createFillerGlass() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(" ");
        meta.getPersistentDataContainer().set(isGuiItemKey, PersistentDataType.BOOLEAN, true);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isGuiItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(isGuiItemKey, PersistentDataType.BOOLEAN);
    }

    // ====================================================
    // GUI BUILDERS
    // ====================================================
    private Inventory buildDomainMenu(Player player, String domainName, int page) {
        Domain domain = domains.get(domainName);
        if (domain == null) return null;

        // Get crafts sorted by cost (cheaper first)
        List<Craft> crafts = new ArrayList<>(domain.getCrafts());
        crafts.sort(Comparator.comparingInt(this::calculateCraftCost));

        int totalPages = (int) Math.ceil((double) crafts.size() / ITEMS_PER_PAGE);
        if (page < 0) page = 0;
        if (page >= totalPages && totalPages > 0) page = totalPages - 1;

        DomainInventoryHolder holder = new DomainInventoryHolder(DomainInventoryHolder.Type.DOMAIN_MENU, domainName, null, page, player.getUniqueId());
        Inventory inv = Bukkit.createInventory(holder, 54, ChatColor.DARK_BLUE + "Domain Menu: " + domainName);

        int start = page * ITEMS_PER_PAGE;
        int end = Math.min(start + ITEMS_PER_PAGE, crafts.size());
        int slot = 0;
        for (int i = start; i < end; i++) {
            Craft craft = crafts.get(i);
            ItemStack display = craft.getDisplayItem().clone();
            ItemMeta meta = display.getItemMeta();
            List<String> lore = new ArrayList<>(meta.hasLore() ? meta.getLore() : new ArrayList<>());
            lore.add("");
            lore.add(ChatColor.GRAY + "Ingredients:");
            for (ItemStack ing : craft.getIngredients()) {
                if (ing != null && ing.getType() != Material.AIR)
                    lore.add(ChatColor.GRAY + " • " + ing.getAmount() + "x " + getItemName(ing));
            }
            lore.add("");
            lore.add(ChatColor.GRAY + "Results:");
            for (ItemStack res : craft.getResults()) {
                if (res != null && res.getType() != Material.AIR)
                    lore.add(ChatColor.GRAY + " • " + res.getAmount() + "x " + getItemName(res));
            }
            lore.add("");
            lore.add(ChatColor.GREEN + "Click to craft!");
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(guiCraftIdKey, PersistentDataType.STRING, craft.getId());
            meta.getPersistentDataContainer().set(isGuiItemKey, PersistentDataType.BOOLEAN, true);
            display.setItemMeta(meta);
            inv.setItem(slot++, display);
        }

        // Fill empty slots with glass
        for (int i = 0; i < ITEMS_PER_PAGE; i++) {
            if (inv.getItem(i) == null) inv.setItem(i, createFillerGlass());
        }

        // Navigation and close buttons
        if (totalPages > 1) {
            if (page > 0) {
                inv.setItem(PREV_PAGE_SLOT, createGuiItem(Material.ARROW, ChatColor.GOLD + "Previous Page"));
            } else {
                inv.setItem(PREV_PAGE_SLOT, createFillerGlass());
            }
            if (page < totalPages - 1) {
                inv.setItem(NEXT_PAGE_SLOT, createGuiItem(Material.ARROW, ChatColor.GOLD + "Next Page"));
            } else {
                inv.setItem(NEXT_PAGE_SLOT, createFillerGlass());
            }
        } else {
            inv.setItem(PREV_PAGE_SLOT, createFillerGlass());
            inv.setItem(NEXT_PAGE_SLOT, createFillerGlass());
        }
        inv.setItem(CLOSE_SLOT, createGuiItem(Material.BARRIER, ChatColor.RED + "Close"));

        return inv;
    }

    private Inventory buildCraftMenu(Player player, String domainName, Craft craft) {
        DomainInventoryHolder holder = new DomainInventoryHolder(DomainInventoryHolder.Type.CRAFT_MENU, domainName, craft.getId(), 0, player.getUniqueId());
        Inventory inv = Bukkit.createInventory(holder, 54, ChatColor.GREEN + "Crafting: " + craft.getName());

        // Display in center
        if (craft.getDisplayItem() != null) {
            ItemStack disp = craft.getDisplayItem().clone();
            ItemMeta meta = disp.getItemMeta();
            meta.getPersistentDataContainer().set(isGuiItemKey, PersistentDataType.BOOLEAN, true);
            disp.setItemMeta(meta);
            inv.setItem(DISPLAY_SLOT, disp);
        }

        // Ingredients - Left square
        List<ItemStack> ingredients = craft.getIngredients();
        for (int i = 0; i < ingredients.size() && i < INGREDIENT_SLOTS.length; i++) {
            if (ingredients.get(i) != null && ingredients.get(i).getType() != Material.AIR) {
                ItemStack item = ingredients.get(i).clone();
                ItemMeta meta = item.getItemMeta();
                meta.getPersistentDataContainer().set(isGuiItemKey, PersistentDataType.BOOLEAN, true);
                item.setItemMeta(meta);
                inv.setItem(INGREDIENT_SLOTS[i], item);
            }
        }

        // Results - Right square
        List<ItemStack> results = craft.getResults();
        for (int i = 0; i < results.size() && i < RESULT_SLOTS.length; i++) {
            if (results.get(i) != null && results.get(i).getType() != Material.AIR) {
                ItemStack item = results.get(i).clone();
                ItemMeta meta = item.getItemMeta();
                meta.getPersistentDataContainer().set(isGuiItemKey, PersistentDataType.BOOLEAN, true);
                item.setItemMeta(meta);
                inv.setItem(RESULT_SLOTS[i], item);
            }
        }

        inv.setItem(40, createGuiItem(Material.CRAFTING_TABLE, ChatColor.GREEN + "CRAFT",
                ChatColor.GRAY + "Click to perform the craft"));
        inv.setItem(BACK_SLOT, createGuiItem(Material.ARROW, ChatColor.GOLD + "Back"));
        inv.setItem(CLOSE_SLOT, createGuiItem(Material.BARRIER, ChatColor.RED + "Close"));

        // Fill the rest with glass
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) inv.setItem(i, createFillerGlass());
        }
        return inv;
    }

    private Inventory buildCraftEditor(Player player, String domainName, Craft craft) {
        DomainInventoryHolder holder = new DomainInventoryHolder(DomainInventoryHolder.Type.CRAFT_EDITOR, domainName, craft != null ? craft.getId() : null, 0, player.getUniqueId());
        Inventory inv = Bukkit.createInventory(holder, 54, ChatColor.DARK_RED + "Edit Craft");

        // Display
        if (craft != null && craft.getDisplayItem() != null) {
            ItemStack item = craft.getDisplayItem().clone();
            ItemMeta meta = item.getItemMeta();
            meta.getPersistentDataContainer().set(isGuiItemKey, PersistentDataType.BOOLEAN, false);
            item.setItemMeta(meta);
            inv.setItem(DISPLAY_SLOT, item);
        }

        // Ingredients (editable)
        if (craft != null) {
            for (int i = 0; i < craft.getIngredients().size() && i < INGREDIENT_SLOTS.length; i++) {
                ItemStack item = craft.getIngredients().get(i);
                if (item != null) {
                    ItemStack clone = item.clone();
                    ItemMeta meta = clone.getItemMeta();
                    meta.getPersistentDataContainer().set(isGuiItemKey, PersistentDataType.BOOLEAN, false);
                    clone.setItemMeta(meta);
                    inv.setItem(INGREDIENT_SLOTS[i], clone);
                }
            }
        }

        // Results (editable)
        if (craft != null) {
            for (int i = 0; i < craft.getResults().size() && i < RESULT_SLOTS.length; i++) {
                ItemStack item = craft.getResults().get(i);
                if (item != null) {
                    ItemStack clone = item.clone();
                    ItemMeta meta = clone.getItemMeta();
                    meta.getPersistentDataContainer().set(isGuiItemKey, PersistentDataType.BOOLEAN, false);
                    clone.setItemMeta(meta);
                    inv.setItem(RESULT_SLOTS[i], clone);
                }
            }
        }

        inv.setItem(40, createGuiItem(Material.EMERALD_BLOCK, ChatColor.GREEN + "SAVE CRAFT"));
        inv.setItem(BACK_SLOT, createGuiItem(Material.ARROW, ChatColor.GOLD + "Back"));
        inv.setItem(CLOSE_SLOT, createGuiItem(Material.BARRIER, ChatColor.RED + "Close"));

        // Instructions
        inv.setItem(48, createGuiItem(Material.OAK_SIGN, ChatColor.AQUA + "Instructions",
                ChatColor.GRAY + "• Place the display item in the center",
                ChatColor.GRAY + "• Ingredients in the LEFT square",
                ChatColor.GRAY + "• Results in the RIGHT square",
                ChatColor.GRAY + "• Click on SAVE"));

        // Fill only non-editable slots
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null && !isEditableSlot(i)) {
                inv.setItem(i, createFillerGlass());
            }
        }
        return inv;
    }

    private boolean isEditableSlot(int slot) {
        return slot == DISPLAY_SLOT ||
               Arrays.stream(INGREDIENT_SLOTS).anyMatch(s -> s == slot) ||
               Arrays.stream(RESULT_SLOTS).anyMatch(s -> s == slot);
    }

    private Inventory buildEditMainMenu(Player player, String domainName) {
        DomainInventoryHolder holder = new DomainInventoryHolder(DomainInventoryHolder.Type.EDIT_MAIN_MENU, domainName, null, 0, player.getUniqueId());
        Inventory inv = Bukkit.createInventory(holder, 27, ChatColor.DARK_RED + "Edit Domain: " + domainName);

        inv.setItem(11, createGuiItem(Material.EMERALD_BLOCK, ChatColor.GREEN + "Add New Craft"));
        inv.setItem(15, createGuiItem(Material.BOOKSHELF, ChatColor.GOLD + "Edit Existing Crafts"));
        inv.setItem(22, createGuiItem(Material.BARRIER, ChatColor.RED + "Close"));

        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) inv.setItem(i, createFillerGlass());
        }
        return inv;
    }

    private Inventory buildEditCraftList(Player player, String domainName, int page) {
        Domain domain = domains.get(domainName);
        if (domain == null) return null;

        // Get crafts sorted by cost (cheaper first)
        List<Craft> crafts = new ArrayList<>(domain.getCrafts());
        crafts.sort(Comparator.comparingInt(this::calculateCraftCost));

        int totalPages = (int) Math.ceil((double) crafts.size() / ITEMS_PER_PAGE);
        if (page < 0) page = 0;
        if (page >= totalPages && totalPages > 0) page = totalPages - 1;

        DomainInventoryHolder holder = new DomainInventoryHolder(DomainInventoryHolder.Type.EDIT_CRAFT_LIST, domainName, null, page, player.getUniqueId());
        Inventory inv = Bukkit.createInventory(holder, 54, ChatColor.DARK_RED + "Edit Crafts: " + domainName);

        int start = page * ITEMS_PER_PAGE;
        int end = Math.min(start + ITEMS_PER_PAGE, crafts.size());
        int slot = 0;
        for (int i = start; i < end; i++) {
            Craft craft = crafts.get(i);
            ItemStack display = craft.getDisplayItem() != null ? craft.getDisplayItem().clone() : new ItemStack(Material.PAPER);
            ItemMeta meta = display.hasItemMeta() ? display.getItemMeta() : Bukkit.getItemFactory().getItemMeta(display.getType());
            List<String> lore = new ArrayList<>(meta.hasLore() ? meta.getLore() : new ArrayList<>());
            lore.add("");
            lore.add(ChatColor.YELLOW + "Click to edit this craft");
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(guiCraftIdKey, PersistentDataType.STRING, craft.getId());
            meta.getPersistentDataContainer().set(isGuiItemKey, PersistentDataType.BOOLEAN, true);
            display.setItemMeta(meta);
            inv.setItem(slot++, display);
        }

        // Fill empty slots with glass
        for (int i = 0; i < ITEMS_PER_PAGE; i++) {
            if (inv.getItem(i) == null) inv.setItem(i, createFillerGlass());
        }

        // Navigation and back/close buttons
        if (totalPages > 1) {
            if (page > 0) {
                inv.setItem(PREV_PAGE_SLOT, createGuiItem(Material.ARROW, ChatColor.GOLD + "Previous Page"));
            } else {
                inv.setItem(PREV_PAGE_SLOT, createFillerGlass());
            }
            if (page < totalPages - 1) {
                inv.setItem(NEXT_PAGE_SLOT, createGuiItem(Material.ARROW, ChatColor.GOLD + "Next Page"));
            } else {
                inv.setItem(NEXT_PAGE_SLOT, createFillerGlass());
            }
        } else {
            inv.setItem(PREV_PAGE_SLOT, createFillerGlass());
            inv.setItem(NEXT_PAGE_SLOT, createFillerGlass());
        }
        inv.setItem(BACK_SLOT, createGuiItem(Material.ARROW, ChatColor.GOLD + "Back"));
        inv.setItem(CLOSE_SLOT, createGuiItem(Material.BARRIER, ChatColor.RED + "Close"));

        return inv;
    }

    // ====================================================
    // CRAFT EXECUTION
    // ====================================================
    private boolean hasEnoughItems(Player player, List<ItemStack> required) {
        Map<ItemKey, Integer> needed = new HashMap<>();
        for (ItemStack item : required) {
            if (item == null || item.getType() == Material.AIR) continue;
            ItemKey key = new ItemKey(item);
            needed.merge(key, item.getAmount(), Integer::sum);
        }

        Map<ItemKey, Integer> playerItems = new HashMap<>();
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType() == Material.AIR) continue;
            ItemKey key = new ItemKey(item);
            playerItems.merge(key, item.getAmount(), Integer::sum);
        }

        for (Map.Entry<ItemKey, Integer> entry : needed.entrySet()) {
            int has = playerItems.getOrDefault(entry.getKey(), 0);
            if (has < entry.getValue()) return false;
        }
        return true;
    }

    private void executeCraft(Player player, Craft craft) {
        if (!hasEnoughItems(player, craft.getIngredients())) {
            player.sendMessage(ChatColor.RED + "You don't have enough materials!");
            return;
        }

        // Remove ingredients
        for (ItemStack ingredient : craft.getIngredients()) {
            if (ingredient != null && ingredient.getType() != Material.AIR) {
                removeItems(player, ingredient);
            }
        }

        // Give results
        for (ItemStack result : craft.getResults()) {
            if (result != null && result.getType() != Material.AIR) {
                HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(result.clone());
                for (ItemStack drop : leftover.values()) {
                    if (drop != null && drop.getType() != Material.AIR) {
                        player.getWorld().dropItemNaturally(player.getLocation(), drop);
                    }
                }
            }
        }

        player.sendMessage(ChatColor.GREEN + "✓ Craft completed successfully!");
    }

    private void removeItems(Player player, ItemStack target) {
        int remaining = target.getAmount();
        for (int i = 0; i < player.getInventory().getSize() && remaining > 0; i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item == null || !item.isSimilar(target)) continue;
            if (item.getAmount() <= remaining) {
                remaining -= item.getAmount();
                player.getInventory().setItem(i, null);
            } else {
                item.setAmount(item.getAmount() - remaining);
                remaining = 0;
            }
        }
    }

    private String getItemName(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return "Air";
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName())
            return item.getItemMeta().getDisplayName();
        return item.getType().name().replace('_', ' ').toLowerCase();
    }

    // ====================================================
    // EVENT LISTENERS
    // ====================================================
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null) return;

        Domain domain = getDomainFromBlock(block);
        if (domain == null) return;

        Player player = event.getPlayer();
        if (!hasPermission(player, domain)) {
            player.sendMessage(ChatColor.RED + "You don't have permission to access this domain!");
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);
        player.openInventory(buildDomainMenu(player, domain.getName(), 0));
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory inv = event.getInventory();
        if (!(inv.getHolder() instanceof DomainInventoryHolder holder)) return;
        if (!holder.getViewerUuid().equals(player.getUniqueId())) {
            event.setCancelled(true);
            player.closeInventory();
            return;
        }

        int slot = event.getSlot();
        ItemStack clicked = event.getCurrentItem();

        // Cancel clicks on non-editable GUI slots (only affects the top inventory)
        if (event.getRawSlot() < event.getInventory().getSize()) {
            if (!(holder.getType() == DomainInventoryHolder.Type.CRAFT_EDITOR && isEditableSlot(slot))) {
                event.setCancelled(true);
            }
        }

        if (clicked == null || clicked.getType() == Material.AIR) return;

        DomainInventoryHolder.Type type = holder.getType();
        String domainName = holder.getDomainName();
        int currentPage = holder.getPage();

        switch (type) {
            case DOMAIN_MENU:
                if (slot == CLOSE_SLOT) {
                    player.closeInventory();
                } else if (slot == PREV_PAGE_SLOT) {
                    player.openInventory(buildDomainMenu(player, domainName, currentPage - 1));
                } else if (slot == NEXT_PAGE_SLOT) {
                    player.openInventory(buildDomainMenu(player, domainName, currentPage + 1));
                } else if (slot < ITEMS_PER_PAGE && clicked.hasItemMeta()) {
                    String craftId = clicked.getItemMeta().getPersistentDataContainer().get(guiCraftIdKey, PersistentDataType.STRING);
                    if (craftId != null) {
                        Domain domain = domains.get(domainName);
                        Craft craft = domain != null ? domain.getCraft(craftId) : null;
                        if (craft != null) {
                            craftSessions.put(player.getUniqueId(), new PlayerCraftSession(domainName, craftId));
                            player.openInventory(buildCraftMenu(player, domainName, craft));
                        }
                    }
                }
                break;

            case CRAFT_MENU:
                if (slot == 40) {
                    String craftId = holder.getCraftId();
                    Domain domain = domains.get(domainName);
                    Craft craft = domain != null ? domain.getCraft(craftId) : null;
                    if (craft != null) executeCraft(player, craft);
                } else if (slot == BACK_SLOT) {
                    player.openInventory(buildDomainMenu(player, domainName, 0));
                } else if (slot == CLOSE_SLOT) {
                    player.closeInventory();
                }
                break;

            case CRAFT_EDITOR:
                if (slot == 40) { // SAVE
                    PlayerEditSession session = editSessions.get(player.getUniqueId());
                    if (session == null) return;

                    ItemStack display = inv.getItem(DISPLAY_SLOT);
                    if (display == null || display.getType() == Material.AIR) {
                        player.sendMessage(ChatColor.RED + "You must define a display item!");
                        return;
                    }

                    List<ItemStack> ingredients = new ArrayList<>();
                    for (int s : INGREDIENT_SLOTS) {
                        ItemStack item = inv.getItem(s);
                        if (item != null && item.getType() != Material.AIR)
                            ingredients.add(item.clone());
                    }

                    List<ItemStack> results = new ArrayList<>();
                    for (int s : RESULT_SLOTS) {
                        ItemStack item = inv.getItem(s);
                        if (item != null && item.getType() != Material.AIR)
                            results.add(item.clone());
                    }

                    if (ingredients.isEmpty() || results.isEmpty()) {
                        player.sendMessage(ChatColor.RED + "Ingredients and results are required!");
                        return;
                    }

                    Craft craft = session.getCraft();
                    craft.setDisplayItem(display.clone());
                    craft.setIngredients(ingredients);
                    craft.setResults(results);

                    if (display.hasItemMeta() && display.getItemMeta().hasDisplayName())
                        craft.setName(display.getItemMeta().getDisplayName());
                    else
                        craft.setName(getItemName(display));

                    if (session.isEditing()) {
                        updateCraft(domainName, craft);
                        player.sendMessage(ChatColor.GREEN + "Craft updated successfully!");
                    } else {
                        addCraft(domainName, craft);
                        player.sendMessage(ChatColor.GREEN + "Craft added successfully!");
                    }

                    editSessions.remove(player.getUniqueId());
                    player.openInventory(buildEditMainMenu(player, domainName));
                } else if (slot == BACK_SLOT) {
                    editSessions.remove(player.getUniqueId());
                    player.openInventory(buildEditMainMenu(player, domainName));
                } else if (slot == CLOSE_SLOT) {
                    editSessions.remove(player.getUniqueId());
                    player.closeInventory();
                }
                break;

            case EDIT_MAIN_MENU:
                if (slot == 11) { // Add New Craft
                    String craftId = UUID.randomUUID().toString();
                    List<ItemStack> empty = new ArrayList<>();
                    Craft newCraft = new Craft(craftId, "New Craft", null, empty, empty);
                    editSessions.put(player.getUniqueId(), new PlayerEditSession(domainName, newCraft, false));
                    player.openInventory(buildCraftEditor(player, domainName, newCraft));
                } else if (slot == 15) { // Edit Existing Crafts
                    player.openInventory(buildEditCraftList(player, domainName, 0));
                } else if (slot == 22) {
                    player.closeInventory();
                }
                break;

            case EDIT_CRAFT_LIST:
                if (slot == BACK_SLOT) {
                    player.openInventory(buildEditMainMenu(player, domainName));
                } else if (slot == CLOSE_SLOT) {
                    player.closeInventory();
                } else if (slot == PREV_PAGE_SLOT) {
                    player.openInventory(buildEditCraftList(player, domainName, currentPage - 1));
                } else if (slot == NEXT_PAGE_SLOT) {
                    player.openInventory(buildEditCraftList(player, domainName, currentPage + 1));
                } else if (slot < ITEMS_PER_PAGE && clicked.hasItemMeta()) {
                    String craftId = clicked.getItemMeta().getPersistentDataContainer().get(guiCraftIdKey, PersistentDataType.STRING);
                    if (craftId != null) {
                        Domain domain = domains.get(domainName);
                        Craft craftToEdit = domain != null ? domain.getCraft(craftId) : null;
                        if (craftToEdit != null) {
                            editSessions.put(player.getUniqueId(), new PlayerEditSession(domainName, craftToEdit, true));
                            player.openInventory(buildCraftEditor(player, domainName, craftToEdit));
                        }
                    }
                }
                break;
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof DomainInventoryHolder holder)) return;
        if (holder.getType() == DomainInventoryHolder.Type.CRAFT_EDITOR) {
            for (int slot : event.getRawSlots()) {
                if (slot < 54 && !isEditableSlot(slot)) {
                    event.setCancelled(true);
                    return;
                }
            }
        } else {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof DomainInventoryHolder) {
            Player p = (Player) event.getPlayer();
            craftSessions.remove(p.getUniqueId());
        }
    }

    // ====================================================
    // COMMAND HANDLER
    // ====================================================
    public class DomainCommand implements CommandExecutor, TabCompleter {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "Only players can use this command.");
                return true;
            }

            if (args.length == 0) {
                sendHelp(player);
                return true;
            }

            switch (args[0].toLowerCase()) {
                case "create":
                    if (!player.isOp() && !player.hasPermission("domaincraft.admin")) {
                        player.sendMessage(ChatColor.RED + "No permission!");
                        return true;
                    }
                    if (args.length < 2) {
                        player.sendMessage(ChatColor.RED + "Usage: /domain create <name>");
                        return true;
                    }
                    if (createDomain(args[1], player)) {
                        player.sendMessage(ChatColor.GREEN + "Domain '" + args[1] + "' created successfully!");
                    } else {
                        player.sendMessage(ChatColor.RED + "Failed to create domain (already exists or no block targeted).");
                    }
                    break;

                case "set":
                    if (!player.isOp() && !player.hasPermission("domaincraft.admin")) {
                        player.sendMessage(ChatColor.RED + "No permission!");
                        return true;
                    }
                    if (args.length < 3) {
                        player.sendMessage(ChatColor.RED + "Usage: /domain set <name> <permission>");
                        return true;
                    }
                    if (setDomainPermission(args[1], args[2])) {
                        player.sendMessage(ChatColor.GREEN + "Permission set successfully!");
                    } else {
                        player.sendMessage(ChatColor.RED + "Domain not found!");
                    }
                    break;

                case "edit":
                    if (args.length < 2) {
                        player.sendMessage(ChatColor.RED + "Usage: /domain edit <name>");
                        return true;
                    }
                    Domain domain = domains.get(args[1]);
                    if (domain == null) {
                        player.sendMessage(ChatColor.RED + "Domain not found!");
                        return true;
                    }
                    if (!hasPermission(player, domain) && !player.isOp() && !player.hasPermission("domaincraft.admin")) {
                        player.sendMessage(ChatColor.RED + "No permission to edit this domain!");
                        return true;
                    }
                    player.openInventory(buildEditMainMenu(player, args[1]));
                    break;

                case "delete":
                    if (!player.isOp() && !player.hasPermission("domaincraft.admin")) {
                        player.sendMessage(ChatColor.RED + "No permission!");
                        return true;
                    }
                    if (args.length < 2) {
                        player.sendMessage(ChatColor.RED + "Usage: /domain delete <name>");
                        return true;
                    }
                    if (deleteDomain(args[1])) {
                        player.sendMessage(ChatColor.GREEN + "Domain deleted successfully!");
                    } else {
                        player.sendMessage(ChatColor.RED + "Domain not found!");
                    }
                    break;

                case "list":
                    if (domains.isEmpty()) {
                        player.sendMessage(ChatColor.YELLOW + "No domains found.");
                    } else {
                        player.sendMessage(ChatColor.GOLD + "=== Domains (" + domains.size() + ") ===");
                        domains.forEach((name, d) -> player.sendMessage(ChatColor.YELLOW + "• " + name +
                                ChatColor.GRAY + " (Perm: " + (d.getRequiredPermission() != null ? d.getRequiredPermission() : "none") +
                                ", Crafts: " + d.getCrafts().size() + ")"));
                    }
                    break;

                default:
                    sendHelp(player);
            }
            return true;
        }

        private void sendHelp(Player p) {
            p.sendMessage(ChatColor.GOLD + "=== DomainCraft Commands ===");
            p.sendMessage(ChatColor.YELLOW + "/domain create <name>");
            p.sendMessage(ChatColor.YELLOW + "/domain set <name> <permission>");
            p.sendMessage(ChatColor.YELLOW + "/domain edit <name>");
            p.sendMessage(ChatColor.YELLOW + "/domain delete <name>");
            p.sendMessage(ChatColor.YELLOW + "/domain list");
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (args.length == 1) return Arrays.asList("create", "set", "edit", "delete", "list").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase())).collect(Collectors.toList());
            if (args.length == 2 && Arrays.asList("set", "edit", "delete").contains(args[0].toLowerCase()))
                return domains.keySet().stream()
                        .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            return Collections.emptyList();
        }
    }

    // ====================================================
    // HELPER CLASSES
    // ====================================================
    private static class ItemKey {
        private final String serialized;

        public ItemKey(ItemStack item) {
            this.serialized = serializeItemStackStatic(item);
        }

        private static String serializeItemStackStatic(ItemStack item) {
            if (item == null || item.getType() == Material.AIR) return "";
            // Clone e força amount = 1 para que a chave seja a mesma independentemente da quantidade
            // (corrige o bug de só aceitar pilhas com tamanho exato)
            ItemStack cloneForKey = item.clone();
            cloneForKey.setAmount(1);
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                 BukkitObjectOutputStream boos = new BukkitObjectOutputStream(baos)) {
                boos.writeObject(cloneForKey);
                return Base64.getEncoder().encodeToString(baos.toByteArray());
            } catch (IOException e) {
                return "";
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ItemKey)) return false;
            return serialized.equals(((ItemKey) o).serialized);
        }

        @Override
        public int hashCode() {
            return serialized.hashCode();
        }
    }

    private static class BlockData implements Serializable {
        String domainName;
        Location location;

        BlockData(String domainName, Location location) {
            this.domainName = domainName;
            this.location = location;
        }
    }

    public static class Domain implements Serializable {
        private String name;
        private Location location;
        private UUID owner;
        private String requiredPermission;
        private Map<String, Craft> crafts = new ConcurrentHashMap<>();
        private long createdAt;

        public Domain(String name, Location location, UUID owner) {
            this.name = name;
            this.location = location;
            this.owner = owner;
            this.createdAt = System.currentTimeMillis();
        }

        public String getName() { return name; }
        public Location getLocation() { return location; }
        public String getRequiredPermission() { return requiredPermission; }
        public void setRequiredPermission(String permission) { this.requiredPermission = permission; }
        public List<Craft> getCrafts() { return new ArrayList<>(crafts.values()); }
        public Craft getCraft(String id) { return crafts.get(id); }
        public void addCraft(Craft craft) { crafts.put(craft.getId(), craft); }
        public void removeCraft(String id) { crafts.remove(id); }
        public void updateCraft(Craft craft) { crafts.put(craft.getId(), craft); }
    }

    public static class Craft implements Serializable {
        private String id;
        private String name;
        private ItemStack displayItem;
        private List<ItemStack> ingredients = new ArrayList<>();
        private List<ItemStack> results = new ArrayList<>();
        private long createdAt;

        public Craft(String id, String name, ItemStack displayItem, List<ItemStack> ingredients, List<ItemStack> results) {
            this.id = id;
            this.name = name;
            this.displayItem = displayItem;
            this.ingredients = ingredients != null ? ingredients : new ArrayList<>();
            this.results = results != null ? results : new ArrayList<>();
            this.createdAt = System.currentTimeMillis();
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public ItemStack getDisplayItem() { return displayItem; }
        public void setDisplayItem(ItemStack displayItem) { this.displayItem = displayItem; }
        public List<ItemStack> getIngredients() { return ingredients; }
        public void setIngredients(List<ItemStack> ingredients) { this.ingredients = ingredients; }
        public List<ItemStack> getResults() { return results; }
        public void setResults(List<ItemStack> results) { this.results = results; }
    }

    private static class PlayerEditSession {
        private final String domainName;
        private final Craft craft;
        private final boolean editing;

        public PlayerEditSession(String domainName, Craft craft, boolean editing) {
            this.domainName = domainName;
            this.craft = craft;
            this.editing = editing;
        }

        public String getDomainName() { return domainName; }
        public Craft getCraft() { return craft; }
        public boolean isEditing() { return editing; }
    }

    private static class PlayerCraftSession {
        private final String domainName;
        private final String craftId;

        public PlayerCraftSession(String domainName, String craftId) {
            this.domainName = domainName;
            this.craftId = craftId;
        }
    }

    public static class DomainInventoryHolder implements InventoryHolder {
        public enum Type { DOMAIN_MENU, CRAFT_MENU, EDIT_MAIN_MENU, EDIT_CRAFT_LIST, CRAFT_EDITOR }

        private final Type type;
        private final String domainName;
        private final String craftId;
        private final int page;
        private final UUID viewerUuid;

        public DomainInventoryHolder(Type type, String domainName, String craftId, int page, UUID viewerUuid) {
            this.type = type;
            this.domainName = domainName;
            this.craftId = craftId;
            this.page = page;
            this.viewerUuid = viewerUuid;
        }

        @Override
        public Inventory getInventory() { return null; }
        public Type getType() { return type; }
        public String getDomainName() { return domainName; }
        public String getCraftId() { return craftId; }
        public int getPage() { return page; }
        public UUID getViewerUuid() { return viewerUuid; }
    }
}
