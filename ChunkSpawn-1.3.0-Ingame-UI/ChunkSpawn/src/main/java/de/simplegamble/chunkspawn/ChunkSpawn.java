package de.simplegamble.chunkspawn;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class ChunkSpawn extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private enum MenuType { MAIN, MAP, CONNECTIONS }
    private static final class MenuHolder implements InventoryHolder {
        private final UUID owner; private final MenuType type; private Inventory inventory;
        private MenuHolder(UUID owner, MenuType type) { this.owner=owner; this.type=type; }
        @Override public Inventory getInventory() { return inventory; }
    }
    private Economy economy;
    private File dataFile;
    private YamlConfiguration data;
    private final Map<UUID, Set<ChunkKey>> plots = new HashMap<>();
    private final Map<UUID, Integer> levels = new HashMap<>();
    private final Map<UUID, Set<UUID>> trusted = new HashMap<>();
    // Consent is directional: the player receiving the request grants permission to the other player.
    private final Map<UUID, Set<UUID>> connectAllowed = new HashMap<>();
    private final Map<ChunkKey, UUID> owners = new HashMap<>();
    private World plotWorld;

    @Override public void onEnable() {
        saveDefaultConfig();
        if (!setupEconomy()) { getLogger().severe("Vault/Economy fehlt! ChunkSpawn wird deaktiviert."); getServer().getPluginManager().disablePlugin(this); return; }
        dataFile = new File(getDataFolder(), "claims.yml");
        loadData();
        String worldName = getConfig().getString("plot-world", "");
        plotWorld = worldName == null || worldName.isBlank() ? Bukkit.getWorlds().get(0) : Bukkit.getWorld(worldName);
        if (plotWorld == null) { getLogger().severe("Plot-Welt nicht gefunden."); getServer().getPluginManager().disablePlugin(this); return; }
        Objects.requireNonNull(getCommand("chunk")).setExecutor(this);
        Objects.requireNonNull(getCommand("chunk")).setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("ChunkSpawn aktiviert. Grundstücke: " + owners.size());
    }
    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false; economy = rsp.getProvider(); return economy != null;
    }
    private void loadData() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        data = YamlConfiguration.loadConfiguration(dataFile);
        plots.clear(); levels.clear(); trusted.clear(); connectAllowed.clear(); owners.clear();
        ConfigurationSection ps = data.getConfigurationSection("players");
        if (ps == null) return;
        for (String id : ps.getKeys(false)) try {
            UUID uuid = UUID.fromString(id); ConfigurationSection p = ps.getConfigurationSection(id); if (p == null) continue;
            Set<ChunkKey> set = new HashSet<>();
            for (String raw : p.getStringList("chunks")) { String[] a=raw.split(":"); if(a.length==3) { ChunkKey k=new ChunkKey(a[0],Integer.parseInt(a[1]),Integer.parseInt(a[2])); set.add(k); owners.put(k,uuid); } }
            plots.put(uuid,set); levels.put(uuid,p.getInt("level",Math.max(1,set.size())));
            trusted.put(uuid, readUuidSet(p.getStringList("trusted")));
            connectAllowed.put(uuid, readUuidSet(p.getStringList("connect-allowed")));
        } catch (Exception ex) { getLogger().warning("Ungültiger Claim-Datensatz: "+id); }
    }
    private Set<UUID> readUuidSet(List<String> list) { Set<UUID> out=new HashSet<>(); for(String s:list) try {out.add(UUID.fromString(s));}catch(Exception ignored){} return out; }
    private void saveData() {
        YamlConfiguration out = new YamlConfiguration();
        for (UUID id : plots.keySet()) {
            String base="players."+id; List<String> cs=new ArrayList<>(); for(ChunkKey k:plots.getOrDefault(id,Set.of())) cs.add(k.world+":"+k.x+":"+k.z);
            out.set(base+".chunks",cs); out.set(base+".level",levels.getOrDefault(id,1));
            out.set(base+".trusted",trusted.getOrDefault(id,Set.of()).stream().map(UUID::toString).toList());
            out.set(base+".connect-allowed",connectAllowed.getOrDefault(id,Set.of()).stream().map(UUID::toString).toList());
        }
        try { out.save(dataFile); } catch(IOException e) { getLogger().severe("Konnte claims.yml nicht speichern: "+e.getMessage()); }
    }
    private record ChunkKey(String world,int x,int z) { static ChunkKey of(Chunk c){return new ChunkKey(c.getWorld().getName(),c.getX(),c.getZ());} }
    private Set<ChunkKey> plot(UUID id) { return plots.computeIfAbsent(id,k->new HashSet<>()); }
    private double price(int level) { return getConfig().getDouble("upgrade-start-price",5000.0)*Math.pow(getConfig().getDouble("upgrade-price-multiplier",1.085),Math.max(0,level-1)); }
    private boolean safeClaim(ChunkKey k) { return k.world.equals(plotWorld.getName()) && !owners.containsKey(k) && plotWorld.getWorldBorder().isInside(new Location(plotWorld,(k.x<<4)+8,plotWorld.getSeaLevel(),(k.z<<4)+8)); }
    private boolean createInitialPlot(Player p) {
        List<ChunkKey> candidates=new ArrayList<>();
        if (!owners.isEmpty()) {
            Random r=new Random(); List<ChunkKey> existing=new ArrayList<>(owners.keySet());
            for(int i=0;i<500;i++){ChunkKey near=existing.get(r.nextInt(existing.size())); int dist=getConfig().getInt("nearby-min-chunks",2)+r.nextInt(Math.max(1,getConfig().getInt("nearby-max-chunks",8)-getConfig().getInt("nearby-min-chunks",2)+1)); int dir=r.nextInt(4); int dx=dir==0?dist:dir==1?-dist:0; int dz=dir==2?dist:dir==3?-dist:0; candidates.add(new ChunkKey(plotWorld.getName(),near.x+dx,near.z+dz));}
        } else {
            Random r=new Random(); Location center=plotWorld.getSpawnLocation(); int radius=getConfig().getInt("first-plot-search-radius",2000)/16;
            for(int i=0;i<1000;i++) candidates.add(new ChunkKey(plotWorld.getName(),(center.getBlockX()>>4)+r.nextInt(radius*2+1)-radius,(center.getBlockZ()>>4)+r.nextInt(radius*2+1)-radius));
        }
        for(ChunkKey k:candidates) if(safeClaim(k)) { plot(p.getUniqueId()).add(k); owners.put(k,p.getUniqueId()); levels.put(p.getUniqueId(),1); saveData(); teleportHome(p); return true; }
        p.sendMessage(ChatColor.RED+"Kein freier Grundstücksplatz gefunden. Bitte Admin kontaktieren."); return false;
    }
    private void teleportHome(Player p) {
        Set<ChunkKey> set=plot(p.getUniqueId()); if(set.isEmpty()) return;
        ChunkKey k=set.iterator().next(); World w=Bukkit.getWorld(k.world); if(w==null)return;
        w.getChunkAt(k.x,k.z).load(); int x=(k.x<<4)+8,z=(k.z<<4)+8; int y=w.getHighestBlockYAt(x,z)+getConfig().getInt("teleport-y-offset",1);
        p.teleport(new Location(w,x+0.5,y,z+0.5));
    }
    @EventHandler public void onJoin(PlayerJoinEvent e) { if(!plots.containsKey(e.getPlayer().getUniqueId())||plot(e.getPlayer().getUniqueId()).isEmpty()) Bukkit.getScheduler().runTask(this,()->createInitialPlot(e.getPlayer())); }
    @EventHandler public void onQuit(PlayerQuitEvent e) { saveData(); }
    @EventHandler public void onDeath(PlayerDeathEvent e) { Player p=e.getEntity(); if(p.getBedSpawnLocation()==null) Bukkit.getScheduler().runTask(this,()->teleportHome(p)); }
    @EventHandler public void onBreak(BlockBreakEvent e) { protect(e.getPlayer(),e.getBlock().getChunk(),e); }
    @EventHandler public void onPlace(BlockPlaceEvent e) { protect(e.getPlayer(),e.getBlock().getChunk(),e); }
    private void protect(Player p,Chunk c,Cancellable event) {
        if(!getConfig().getBoolean("protect-claims",true))return;
        UUID owner=owners.get(ChunkKey.of(c)); if(owner==null||owner.equals(p.getUniqueId())||p.hasPermission("chunkspawn.admin")||trusted.getOrDefault(owner,Set.of()).contains(p.getUniqueId()))return;
        event.setCancelled(true); p.sendMessage(ChatColor.RED+"Dieses Grundstück gehört "+Bukkit.getOfflinePlayer(owner).getName()+".");
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] a) {
        if(a.length==0){sender.sendMessage(ChatColor.YELLOW+"/chunk menu, info, home, upgrade [norden|osten|sueden|westen], connect <allow|deny|remove> <Spieler>, trust <Spieler>, reload");return true;}
        if(a[0].equalsIgnoreCase("reload")){if(!sender.hasPermission("chunkspawn.admin")){sender.sendMessage(ChatColor.RED+"Keine Rechte.");return true;}reloadConfig();sender.sendMessage(ChatColor.GREEN+"Config neu geladen.");return true;}
        if(!(sender instanceof Player p)){sender.sendMessage("Nur Spieler können das benutzen.");return true;}
        UUID id=p.getUniqueId();
        switch(a[0].toLowerCase(Locale.ROOT)) {
            case "menu" -> openMainMenu(p);
            case "info" -> { sender.sendMessage(ChatColor.GOLD+"ChunkSpawn Grundstück"); sender.sendMessage(ChatColor.YELLOW+"Chunks: "+plot(id).size()+" | Level: "+levels.getOrDefault(id,1)); sender.sendMessage(ChatColor.YELLOW+"Nächstes Upgrade: "+String.format(Locale.US,"%.2f",price(levels.getOrDefault(id,1)))+" $"); }
            case "home" -> { if(plot(id).isEmpty())createInitialPlot(p); else teleportHome(p); }
            case "upgrade" -> upgrade(p,a.length>1?a[1]:"");
            case "trust" -> { if(a.length<2){p.sendMessage(ChatColor.RED+"Benutzung: /chunk trust <Spieler>");break;} Player target=Bukkit.getPlayerExact(a[1]); if(target==null){p.sendMessage(ChatColor.RED+"Spieler muss online sein.");break;} trusted.computeIfAbsent(id,k->new HashSet<>()).add(target.getUniqueId()); saveData(); p.sendMessage(ChatColor.GREEN+target.getName()+" darf auf deinem Grundstück bauen."); }
            case "connect" -> connect(p,a);
            default -> p.sendMessage(ChatColor.RED+"Unbekannter Befehl. /chunk");
        } return true;
    }
    private ItemStack item(Material material, String name, String... lore) {
        ItemStack stack = new ItemStack(material); ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(name); if (lore.length > 0) meta.setLore(Arrays.asList(lore)); stack.setItemMeta(meta); return stack;
    }
    private Inventory createMenu(Player p, MenuType type, int size, String title) {
        MenuHolder holder = new MenuHolder(p.getUniqueId(), type);
        Inventory inv = Bukkit.createInventory(holder, size, title); holder.inventory = inv; return inv;
    }
    private void openMainMenu(Player p) {
        Inventory inv = createMenu(p, MenuType.MAIN, 27, ChatColor.DARK_GREEN + "ChunkSpawn » Menü");
        int level = levels.getOrDefault(p.getUniqueId(), 1);
        inv.setItem(10, item(Material.FILLED_MAP, ChatColor.GREEN + "Chunk-Karte", ChatColor.GRAY + "Eigene, freie und fremde Chunks", ChatColor.YELLOW + "Klicken zum Öffnen"));
        inv.setItem(12, item(Material.EMERALD, ChatColor.GOLD + "Claim erweitern", ChatColor.GRAY + "Level: " + level, ChatColor.GRAY + "Kosten: " + String.format(Locale.US,"%.2f",price(level)) + " Coins", ChatColor.YELLOW + "Klicken für nächsten freien Chunk"));
        inv.setItem(14, item(Material.OAK_DOOR, ChatColor.AQUA + "Verbindungen", ChatColor.GRAY + "Verwalte Freigaben mit Spielern", ChatColor.YELLOW + "Klicken zum Öffnen"));
        inv.setItem(16, item(Material.COMPASS, ChatColor.YELLOW + "Zu deinem Claim", ChatColor.GRAY + "Teleportiert zu deinem Grundstück"));
        inv.setItem(22, item(Material.PAPER, ChatColor.WHITE + "Dein Grundstück", ChatColor.GRAY + "Chunks: " + plot(p.getUniqueId()).size(), ChatColor.GRAY + "Level: " + level));
        p.openInventory(inv);
    }
    private ChunkKey mapAnchor(UUID id) {
        Set<ChunkKey> own = plot(id); if (own.isEmpty()) return null;
        return own.stream().sorted(Comparator.comparing((ChunkKey k)->k.world).thenComparingInt(k->k.x).thenComparingInt(k->k.z)).findFirst().orElse(null);
    }
    private void openMapMenu(Player p) {
        Inventory inv = createMenu(p, MenuType.MAP, 54, ChatColor.DARK_GREEN + "ChunkSpawn » Chunk-Karte");
        UUID id=p.getUniqueId(); ChunkKey anchor=mapAnchor(id);
        if(anchor==null){ inv.setItem(22,item(Material.BARRIER,ChatColor.RED+"Kein Claim gefunden")); p.openInventory(inv); return; }
        for(int row=0;row<3;row++) for(int col=0;col<7;col++) {
            int dx=col-3,dz=row-1; ChunkKey k=new ChunkKey(anchor.world,anchor.x+dx,anchor.z+dz); UUID owner=owners.get(k);
            Material material; String name; String[] lore;
            if(owner!=null && owner.equals(id)){material=Material.LIME_STAINED_GLASS_PANE;name=ChatColor.GREEN+"Dein Chunk";lore=new String[]{ChatColor.GRAY+"Chunk: "+k.x+", "+k.z};}
            else if(owner!=null){material=Material.RED_STAINED_GLASS_PANE;name=ChatColor.RED+"Fremder Claim";lore=new String[]{ChatColor.GRAY+"Besitzer: "+Optional.ofNullable(Bukkit.getOfflinePlayer(owner).getName()).orElse("Unbekannt"),ChatColor.DARK_GRAY+"Geschützt"};}
            else if(isAdjacentToPlot(id,k)){material=Material.YELLOW_STAINED_GLASS_PANE;name=ChatColor.YELLOW+"Freier Chunk";lore=new String[]{ChatColor.GRAY+"Chunk: "+k.x+", "+k.z,ChatColor.GRAY+"Kosten: "+String.format(Locale.US,"%.2f",price(levels.getOrDefault(id,1)))+" Coins",ChatColor.GREEN+"Klicken zum Kaufen"};}
            else {material=Material.GRAY_STAINED_GLASS_PANE;name=ChatColor.GRAY+"Freie Fläche";lore=new String[]{ChatColor.GRAY+"Nicht direkt an deinen Claim angrenzend"};}
            inv.setItem(10+col+row*9,item(material,name,lore));
        }
        inv.setItem(45,item(Material.LIME_DYE,ChatColor.GREEN+"Dein Claim"));
        inv.setItem(46,item(Material.YELLOW_DYE,ChatColor.YELLOW+"Erweiterbar"));
        inv.setItem(47,item(Material.RED_DYE,ChatColor.RED+"Fremder Claim"));
        inv.setItem(49,item(Material.EMERALD,ChatColor.GOLD+"Upgrade automatisch",ChatColor.GRAY+"Kosten: "+String.format(Locale.US,"%.2f",price(levels.getOrDefault(id,1)))+" Coins",ChatColor.YELLOW+"Klicken zum Erweitern"));
        inv.setItem(53,item(Material.ARROW,ChatColor.YELLOW+"Zurück")); p.openInventory(inv);
    }
    private boolean isAdjacentToPlot(UUID id, ChunkKey candidate) {
        for(ChunkKey own:plot(id)) if(own.world.equals(candidate.world) && Math.abs(own.x-candidate.x)+Math.abs(own.z-candidate.z)==1) return true;
        return false;
    }
    private void buySpecificChunk(Player p, ChunkKey candidate) {
        UUID id=p.getUniqueId(); int level=levels.getOrDefault(id,1); double cost=price(level);
        if(level>=getConfig().getInt("max-level",100)){p.sendMessage(ChatColor.RED+"Maximales Level erreicht.");return;}
        if(!safeClaim(candidate)||!isAdjacentToPlot(id,candidate)){p.sendMessage(ChatColor.RED+"Dieser Chunk ist nicht verfügbar.");return;}
        if(economy.getBalance(p)<cost){p.sendMessage(ChatColor.RED+"Du brauchst "+String.format(Locale.US,"%.2f",cost)+" Coins.");return;}
        if(!economy.withdrawPlayer(p,cost).transactionSuccess()){p.sendMessage(ChatColor.RED+"Zahlung fehlgeschlagen; Claim wurde nicht erweitert.");return;}
        plot(id).add(candidate); owners.put(candidate,id); levels.put(id,level+1); saveData(); p.sendMessage(ChatColor.GREEN+"Chunk gekauft! Neues Level: "+(level+1)+". Bezahlt: "+String.format(Locale.US,"%.2f",cost)+" Coins."); openMapMenu(p);
    }
    private void openConnectionsMenu(Player p) {
        Inventory inv=createMenu(p,MenuType.CONNECTIONS,27,ChatColor.DARK_AQUA+"ChunkSpawn » Verbindungen");
        UUID id=p.getUniqueId(); int slot=0;
        for(Player target:Bukkit.getOnlinePlayers()) {
            if(target.getUniqueId().equals(id)||slot>=18) continue;
            boolean allowed=connectAllowed.getOrDefault(id,Set.of()).contains(target.getUniqueId());
            ItemStack head=new ItemStack(Material.PLAYER_HEAD); SkullMeta meta=(SkullMeta)head.getItemMeta(); meta.setOwningPlayer(target); meta.setDisplayName((allowed?ChatColor.GREEN:ChatColor.YELLOW)+target.getName());
            meta.setLore(Arrays.asList(ChatColor.GRAY+"Verbindungsfreigabe: "+(allowed?ChatColor.GREEN+"Erlaubt":ChatColor.RED+"Nicht erlaubt"),ChatColor.YELLOW+(allowed?"Klicken zum Widerrufen":"Klicken zum Erlauben"))); head.setItemMeta(meta); inv.setItem(slot++,head);
        }
        inv.setItem(22,item(Material.ARROW,ChatColor.YELLOW+"Zurück"));
        if(slot==0) inv.setItem(13,item(Material.BARRIER,ChatColor.GRAY+"Keine anderen Spieler online"));
        p.openInventory(inv);
    }
    @EventHandler public void onMenuClick(InventoryClickEvent e) {
        if(!(e.getWhoClicked() instanceof Player p) || !(e.getInventory().getHolder() instanceof MenuHolder holder)) return;
        e.setCancelled(true); if(!holder.owner.equals(p.getUniqueId()) || e.getRawSlot()<0 || e.getRawSlot()>=e.getInventory().getSize()) return;
        int slot=e.getRawSlot();
        if(holder.type==MenuType.MAIN) {
            if(slot==10)openMapMenu(p); else if(slot==12){upgrade(p,"");openMainMenu(p);} else if(slot==14)openConnectionsMenu(p); else if(slot==16){teleportHome(p);p.closeInventory();}
        } else if(holder.type==MenuType.MAP) {
            if(slot==53){openMainMenu(p);return;} if(slot==49){upgrade(p,"");openMapMenu(p);return;}
            int row=(slot-10)/9,col=(slot-10)%9;
            if(row>=0&&row<3&&col>=0&&col<7){ChunkKey anchor=mapAnchor(holder.owner);if(anchor!=null){ChunkKey candidate=new ChunkKey(anchor.world,anchor.x+col-3,anchor.z+row-1);if(owners.get(candidate)==null)buySpecificChunk(p,candidate);}}
        } else if(holder.type==MenuType.CONNECTIONS) {
            if(slot==22){openMainMenu(p);return;} ItemStack clicked=e.getCurrentItem(); if(clicked==null||clicked.getType()!=Material.PLAYER_HEAD||!(clicked.getItemMeta() instanceof SkullMeta meta))return;
            OfflinePlayer target=meta.getOwningPlayer(); if(target==null)return; UUID me=p.getUniqueId(),other=target.getUniqueId();
            if(connectAllowed.computeIfAbsent(me,k->new HashSet<>()).remove(other)){p.sendMessage(ChatColor.YELLOW+"Verbindungsfreigabe widerrufen: "+target.getName());Player online=target.getPlayer();if(online!=null)online.sendMessage(ChatColor.YELLOW+p.getName()+" hat die Verbindungsfreigabe widerrufen.");}
            else {connectAllowed.computeIfAbsent(me,k->new HashSet<>()).add(other);p.sendMessage(ChatColor.GREEN+"Verbindung für "+target.getName()+" freigegeben.");Player online=target.getPlayer();if(online!=null)online.sendMessage(ChatColor.GREEN+p.getName()+" hat eine Verbindungsfreigabe erteilt.");}
            saveData();openConnectionsMenu(p);
        }
    }
    private void upgrade(Player p,String direction) {
        UUID id=p.getUniqueId(); int level=levels.getOrDefault(id,1); if(level>=getConfig().getInt("max-level",100)){p.sendMessage(ChatColor.RED+"Maximales Level erreicht.");return;}
        double cost=price(level); if(economy.getBalance(p)<cost){p.sendMessage(ChatColor.RED+"Du brauchst "+String.format(Locale.US,"%.2f",cost)+" $. ");return;}
        List<int[]> dirs=new ArrayList<>(); switch(direction.toLowerCase(Locale.ROOT)) {
            case "norden","north" -> dirs.add(new int[]{0,-1}); case "osten","east" -> dirs.add(new int[]{1,0}); case "sueden","süden","south" -> dirs.add(new int[]{0,1}); case "westen","west" -> dirs.add(new int[]{-1,0});
            default -> { dirs.add(new int[]{0,-1});dirs.add(new int[]{1,0});dirs.add(new int[]{0,1});dirs.add(new int[]{-1,0}); Collections.rotate(dirs,-Math.floorMod(level-1,4)); }
        }
        ChunkKey selected=null;
        outer: for(int[] d:dirs) for(ChunkKey own:plot(id)) { ChunkKey candidate=new ChunkKey(own.world,own.x+d[0],own.z+d[1]); if(safeClaim(candidate)){selected=candidate;break outer;} }
        if(selected==null){p.sendMessage(ChatColor.RED+"Kein freier angrenzender Chunk in dieser Richtung. Es wurde nichts abgebucht.");return;}
        if(!economy.withdrawPlayer(p,cost).transactionSuccess()){p.sendMessage(ChatColor.RED+"Zahlung fehlgeschlagen; Claim wurde nicht erweitert.");return;} plot(id).add(selected); owners.put(selected,id); levels.put(id,level+1); saveData(); p.sendMessage(ChatColor.GREEN+"Chunk gekauft! Neues Level: "+(level+1)+". Bezahlt: "+String.format(Locale.US,"%.2f",cost)+" $");
    }
    private void connect(Player p,String[] a) {
        if(a.length<3){p.sendMessage(ChatColor.YELLOW+"/chunk connect allow <Spieler> | /chunk connect deny <Spieler> | /chunk connect remove <Spieler>");return;}
        Player target=Bukkit.getPlayerExact(a[2]); if(target==null){p.sendMessage(ChatColor.RED+"Spieler muss online sein.");return;}
        UUID me=p.getUniqueId(), other=target.getUniqueId();
        if(a[1].equalsIgnoreCase("allow")){connectAllowed.computeIfAbsent(me,k->new HashSet<>()).add(other);saveData();p.sendMessage(ChatColor.GREEN+"Du erlaubst "+target.getName()+", eine Verbindung zu deinem Grundstück herzustellen.");target.sendMessage(ChatColor.GREEN+p.getName()+" erlaubt dir eine Grundstücksverbindung.");}
        else if(a[1].equalsIgnoreCase("deny")){connectAllowed.computeIfAbsent(me,k->new HashSet<>()).remove(other);saveData();p.sendMessage(ChatColor.YELLOW+"Du hast die Verbindungsfreigabe für "+target.getName()+" entfernt.");target.sendMessage(ChatColor.YELLOW+p.getName()+" hat die Verbindungsfreigabe zurückgenommen.");}
        else if(a[1].equalsIgnoreCase("remove")){connectAllowed.computeIfAbsent(me,k->new HashSet<>()).remove(other);connectAllowed.computeIfAbsent(other,k->new HashSet<>()).remove(me);saveData();p.sendMessage(ChatColor.YELLOW+"Verbindungserlaubnis mit "+target.getName()+" auf beiden Seiten entfernt.");}
        else p.sendMessage(ChatColor.RED+"Benutzung: /chunk connect allow|remove <Spieler>");
    }
    @Override public List<String> onTabComplete(CommandSender s,Command c,String a,String[] args){ if(args.length==1)return Arrays.asList("menu","info","home","upgrade","connect","trust","reload"); if(args.length==2&&args[0].equalsIgnoreCase("connect"))return Arrays.asList("allow","deny","remove"); if(args.length==3&&(args[0].equalsIgnoreCase("connect")||args[0].equalsIgnoreCase("trust")))return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(); if(args.length==2&&args[0].equalsIgnoreCase("upgrade"))return Arrays.asList("norden","osten","sueden","westen"); return Collections.emptyList(); }
    @Override public void onDisable(){if(dataFile!=null)saveData();}
}
