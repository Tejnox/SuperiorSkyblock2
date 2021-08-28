package com.bgsoftware.superiorskyblock.handlers;

import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.handlers.PlayersManager;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.island.PlayerRole;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.bgsoftware.superiorskyblock.data.DatabaseResult;
import com.bgsoftware.superiorskyblock.data.PlayersDatabaseBridge;
import com.bgsoftware.superiorskyblock.island.SPlayerRole;
import com.bgsoftware.superiorskyblock.utils.threads.Executor;
import com.bgsoftware.superiorskyblock.player.SuperiorNPCPlayer;
import com.google.common.base.Preconditions;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@SuppressWarnings("WeakerAccess")
public final class PlayersHandler extends AbstractHandler implements PlayersManager {

    private static final int GUEST_ROLE_INDEX = -2, COOP_ROLE_INDEX = -1;

    private final Map<Integer, PlayerRole> rolesByWeight = new HashMap<>();
    private final Map<Integer, PlayerRole> rolesById = new HashMap<>();
    private final Map<String, PlayerRole> rolesByName = new HashMap<>();
    private final Map<UUID, SuperiorPlayer> players = new ConcurrentHashMap<>();

    private int lastRole = Integer.MIN_VALUE;

    public PlayersHandler(SuperiorSkyblockPlugin plugin){
        super(plugin);
    }

    @Override
    public void loadData(){
        ConfigurationSection rolesSection = plugin.getSettings().islandRolesSection;
        loadRole(rolesSection.getConfigurationSection("guest"), GUEST_ROLE_INDEX, null);
        loadRole(rolesSection.getConfigurationSection("coop"), COOP_ROLE_INDEX, (SPlayerRole) getGuestRole());
        SPlayerRole previousRole = (SPlayerRole) getCoopRole();
        for(String roleSection : rolesSection.getConfigurationSection("ladder").getKeys(false))
            previousRole = (SPlayerRole) getPlayerRole(loadRole(rolesSection.getConfigurationSection("ladder." + roleSection), 0, previousRole));
    }

    @Override
    public SuperiorPlayer getSuperiorPlayer(String name){
        if(name == null)
            return null;

        for(SuperiorPlayer superiorPlayer : players.values()){
            if(superiorPlayer.getName().equalsIgnoreCase(name))
                return superiorPlayer;
        }

        return null;
    }

    public SuperiorPlayer getSuperiorPlayer(CommandSender commandSender) {
        return getSuperiorPlayer((Player) commandSender);
    }

    @Override
    public SuperiorPlayer getSuperiorPlayer(Player player) {
        Preconditions.checkNotNull(player, "player parameter cannot be null.");
        return player.hasMetadata("NPC") ? new SuperiorNPCPlayer(player) : getSuperiorPlayer(player.getUniqueId());
    }

    @Override
    public SuperiorPlayer getSuperiorPlayer(UUID uuid){
        Preconditions.checkNotNull(uuid, "uuid parameter cannot be null.");
        if(!players.containsKey(uuid)) {
            players.put(uuid, plugin.getFactory().createPlayer(uuid));
            Executor.async(() -> plugin.getDataHandler().insertPlayer(players.get(uuid)), 1L);
        }
        return players.get(uuid);
    }

    public List<SuperiorPlayer> matchAllPlayers(Predicate<? super SuperiorPlayer> predicate){
        return players.values().stream().filter(predicate).collect(Collectors.toList());
    }

    @Override
    public List<SuperiorPlayer> getAllPlayers() {
        return new ArrayList<>(players.values());
    }

    @Override
    public PlayerRole getPlayerRole(int index) {
        return rolesByWeight.get(index);
    }

    @Override
    public PlayerRole getPlayerRoleFromId(int id) {
        return rolesById.get(id);
    }

    @Override
    public PlayerRole getPlayerRole(String name) {
        Preconditions.checkNotNull(name, "name parameter cannot be null.");
        PlayerRole playerRole = rolesByName.get(name.toUpperCase());

        Preconditions.checkArgument(playerRole != null, "Invalid role name: " + name);

        return playerRole;
    }

    @Override
    public PlayerRole getDefaultRole() {
        return getPlayerRole(0);
    }

    @Override
    public PlayerRole getLastRole() {
        return getPlayerRole(lastRole);
    }

    @Override
    public PlayerRole getGuestRole() {
        return getPlayerRole(GUEST_ROLE_INDEX);
    }

    @Override
    public PlayerRole getCoopRole() {
        return getPlayerRole(COOP_ROLE_INDEX);
    }

    @Override
    public List<PlayerRole> getRoles(){
        return rolesById.keySet().stream().sorted().map(rolesById::get).collect(Collectors.toList());
    }

    public SuperiorPlayer loadPlayer(DatabaseResult resultSet) {
        UUID player = UUID.fromString(resultSet.getString("uuid"));
        SuperiorPlayer superiorPlayer = plugin.getFactory().createPlayer(resultSet);
        players.put(player, superiorPlayer);
        return superiorPlayer;
    }

    public void replacePlayers(SuperiorPlayer originPlayer, SuperiorPlayer newPlayer){
        players.remove(originPlayer.getUniqueId());

        for(Island island : plugin.getGrid().getIslands())
            island.replacePlayers(originPlayer, newPlayer);

        newPlayer.merge(originPlayer);
    }

    // Updating last time status
    public void savePlayers(){
        Bukkit.getOnlinePlayers().stream().map(this::getSuperiorPlayer).forEach(PlayersDatabaseBridge::saveLastTimeStatus);
    }

    private int loadRole(ConfigurationSection section, int type, SPlayerRole previousRole){
        int weight = section.getInt("weight", type);
        int id = section.getInt("id", weight);
        String name = section.getString("name");

        PlayerRole playerRole = new SPlayerRole(name, id, weight, section.getStringList("permissions"), previousRole);

        rolesByWeight.put(weight, playerRole);
        rolesById.put(id, playerRole);
        rolesByName.put(name.toUpperCase(), playerRole);

        if(weight > lastRole)
            lastRole = weight;

        return weight;
    }

}
