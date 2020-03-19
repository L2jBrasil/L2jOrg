package org.l2j.gameserver.data.sql.impl;

import io.github.joealisson.primitive.CHashIntMap;
import io.github.joealisson.primitive.IntMap;
import org.l2j.commons.database.DatabaseFactory;
import org.l2j.commons.threading.ThreadPool;
import org.l2j.gameserver.Config;
import org.l2j.gameserver.communitybbs.Manager.ForumsBBSManager;
import org.l2j.gameserver.data.database.dao.ClanDAO;
import org.l2j.gameserver.data.xml.impl.ClanHallManager;
import org.l2j.gameserver.enums.ClanWarState;
import org.l2j.gameserver.enums.UserInfoType;
import org.l2j.gameserver.idfactory.IdFactory;
import org.l2j.gameserver.instancemanager.ClanEntryManager;
import org.l2j.gameserver.instancemanager.FortDataManager;
import org.l2j.gameserver.instancemanager.FortSiegeManager;
import org.l2j.gameserver.instancemanager.SiegeManager;
import org.l2j.gameserver.model.Clan;
import org.l2j.gameserver.model.ClanMember;
import org.l2j.gameserver.model.ClanPrivilege;
import org.l2j.gameserver.model.ClanWar;
import org.l2j.gameserver.model.actor.instance.Player;
import org.l2j.gameserver.model.entity.ClanHall;
import org.l2j.gameserver.model.entity.Fort;
import org.l2j.gameserver.model.entity.FortSiege;
import org.l2j.gameserver.model.entity.Siege;
import org.l2j.gameserver.model.events.EventDispatcher;
import org.l2j.gameserver.model.events.impl.character.player.OnPlayerClanCreate;
import org.l2j.gameserver.model.events.impl.character.player.OnPlayerClanDestroy;
import org.l2j.gameserver.model.events.impl.clan.OnClanWarFinish;
import org.l2j.gameserver.network.SystemMessageId;
import org.l2j.gameserver.network.serverpackets.PledgeShowInfoUpdate;
import org.l2j.gameserver.network.serverpackets.PledgeShowMemberListAll;
import org.l2j.gameserver.network.serverpackets.PledgeShowMemberListUpdate;
import org.l2j.gameserver.network.serverpackets.SystemMessage;
import org.l2j.gameserver.util.EnumIntBitmask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.l2j.commons.database.DatabaseAccess.getDAO;
import static org.l2j.commons.util.Util.isAlphaNumeric;

/**
 * This class loads the clan related data.
 * @author JoeAlisson
 */
public class ClanTable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClanTable.class);
    private final IntMap<Clan> clans = new CHashIntMap<>();

    private ClanTable() {
    }

    private void load() {
        // forums has to be loaded before clan data, because of last forum id used should have also memo included
        if (Config.ENABLE_COMMUNITY_BOARD) {
            ForumsBBSManager.getInstance().initRoot();
        }

        getDAO(ClanDAO.class).findAll().forEach(data -> {
            clans.put(data.getId(), new Clan(data));

            if (data.getDissolvingExpiryTime() != 0) {
                scheduleRemoveClan(data.getId());
            }
        });

        allianceCheck();
        restoreClanWars();
    }

    /**
     * Gets the clans.
     *
     * @return the clans
     */
    public Collection<Clan> getClans() {
        return clans.values();
    }

    /**
     * Gets the clan count.
     *
     * @return the clan count
     */
    public int getClanCount() {
        return clans.size();
    }

    /**
     * @param clanId
     * @return
     */
    public Clan getClan(int clanId) {
        return clans.get(clanId);
    }

    public Clan getClanByName(String clanName) {
        return clans.values().stream().filter(c -> c.getName().equalsIgnoreCase(clanName)).findFirst().orElse(null);
    }

    /**
     * Creates a new clan and store clan info to database
     *
     * @param player
     * @param clanName
     * @return NULL if clan with same name already exists
     */
    public Clan createClan(Player player, String clanName) {
        if (null == player) {
            return null;
        }

        if (10 > player.getLevel()) {
            player.sendPacket(SystemMessageId.YOU_DO_NOT_MEET_THE_CRITERIA_IN_ORDER_TO_CREATE_A_CLAN);
            return null;
        }
        if (0 != player.getClanId()) {
            player.sendPacket(SystemMessageId.YOU_HAVE_FAILED_TO_CREATE_A_CLAN);
            return null;
        }
        if (System.currentTimeMillis() < player.getClanCreateExpiryTime()) {
            player.sendPacket(SystemMessageId.YOU_MUST_WAIT_10_DAYS_BEFORE_CREATING_A_NEW_CLAN);
            return null;
        }
        if (!isAlphaNumeric(clanName) || (2 > clanName.length())) {
            player.sendPacket(SystemMessageId.CLAN_NAME_IS_INVALID);
            return null;
        }
        if (16 < clanName.length()) {
            player.sendPacket(SystemMessageId.CLAN_NAME_S_LENGTH_IS_INCORRECT);
            return null;
        }

        if (null != getClanByName(clanName)) {
            // clan name is already taken
            final SystemMessage sm = SystemMessage.getSystemMessage(SystemMessageId.S1_ALREADY_EXISTS);
            sm.addString(clanName);
            player.sendPacket(sm);
            return null;
        }

        final Clan clan = new Clan(IdFactory.getInstance().getNextId(), clanName);
        final ClanMember leader = new ClanMember(clan, player);
        clan.setLeader(leader);
        leader.setPlayerInstance(player);
        clan.store();
        player.setClan(clan);
        player.setPledgeClass(ClanMember.calculatePledgeClass(player));
        player.setClanPrivileges(new EnumIntBitmask<>(ClanPrivilege.class, true));

        clans.put(clan.getId(), clan);

        // should be update packet only
        player.sendPacket(new PledgeShowInfoUpdate(clan));
        PledgeShowMemberListAll.sendAllTo(player);
        player.sendPacket(new PledgeShowMemberListUpdate(player));
        player.sendPacket(SystemMessageId.YOUR_CLAN_HAS_BEEN_CREATED);
        player.broadcastUserInfo(UserInfoType.RELATION, UserInfoType.CLAN);

        // Notify to scripts
        EventDispatcher.getInstance().notifyEventAsync(new OnPlayerClanCreate(player, clan));
        return clan;
    }

    public synchronized void destroyClan(int clanId) {
        final Clan clan = getClan(clanId);
        if (clan == null) {
            return;
        }

        clan.broadcastToOnlineMembers(SystemMessage.getSystemMessage(SystemMessageId.CLAN_HAS_DISPERSED));

        ClanEntryManager.getInstance().removeFromClanList(clan.getId());

        final int castleId = clan.getCastleId();
        if (castleId == 0) {
            for (Siege siege : SiegeManager.getInstance().getSieges()) {
                siege.removeSiegeClan(clan);
            }
        }

        final int fortId = clan.getFortId();
        if (fortId == 0) {
            for (FortSiege siege : FortSiegeManager.getInstance().getSieges()) {
                siege.removeAttacker(clan);
            }
        }

        final ClanHall hall = ClanHallManager.getInstance().getClanHallByClan(clan);
        if (hall != null) {
            hall.setOwner(null);
        }

        final ClanMember leaderMember = clan.getLeader();
        if (leaderMember == null) {
            clan.getWarehouse().destroyAllItems("ClanRemove", null, null);
        } else {
            clan.getWarehouse().destroyAllItems("ClanRemove", clan.getLeader().getPlayerInstance(), null);
        }

        for (ClanMember member : clan.getMembers()) {
            clan.removeClanMember(member.getObjectId(), 0);
        }

        clans.remove(clanId);
        IdFactory.getInstance().releaseId(clanId);
        getDAO(ClanDAO.class).deleteClan(clanId);
        CrestTable.getInstance().removeCrests(clan);

        if (fortId != 0) {
            final Fort fort = FortDataManager.getInstance().getFortById(fortId);
            if (fort != null) {
                final Clan owner = fort.getOwnerClan();
                if (clan == owner) {
                    fort.removeOwner(true);
                }
            }
        }

        // Notify to scripts
        EventDispatcher.getInstance().notifyEventAsync(new OnPlayerClanDestroy(leaderMember, clan));
    }

    public void scheduleRemoveClan(int clanId) {
        ThreadPool.schedule(() ->
        {
            if (getClan(clanId) == null) {
                return;
            }
            if (getClan(clanId).getDissolvingExpiryTime() != 0) {
                destroyClan(clanId);
            }
        }, Math.max(getClan(clanId).getDissolvingExpiryTime() - System.currentTimeMillis(), 300000));
    }

    public boolean isAllyExists(String allyName) {
        for (Clan clan : clans.values()) {
            if ((clan.getAllyName() != null) && clan.getAllyName().equalsIgnoreCase(allyName)) {
                return true;
            }
        }
        return false;
    }

    public void storeClanWars(ClanWar war) {
        try (Connection con = DatabaseFactory.getInstance().getConnection();
             PreparedStatement ps = con.prepareStatement("REPLACE INTO clan_wars (clan1, clan2, clan1Kill, clan2Kill, winnerClan, startTime, endTime, state) VALUES(?,?,?,?,?,?,?,?)")) {
            ps.setInt(1, war.getAttackerClanId());
            ps.setInt(2, war.getAttackedClanId());
            ps.setInt(3, war.getAttackerKillCount());
            ps.setInt(4, war.getAttackedKillCount());
            ps.setInt(5, war.getWinnerClanId());
            ps.setLong(6, war.getStartTime());
            ps.setLong(7, war.getEndTime());
            ps.setInt(8, war.getState().ordinal());
            ps.execute();
        } catch (Exception e) {
            LOGGER.error("Error storing clan wars data: " + e);
        }
    }

    public void deleteClanWars(int clanId1, int clanId2) {
        final Clan clan1 = getInstance().getClan(clanId1);
        final Clan clan2 = getInstance().getClan(clanId2);

        EventDispatcher.getInstance().notifyEventAsync(new OnClanWarFinish(clan1, clan2));

        clan1.deleteWar(clan2.getId());
        clan2.deleteWar(clan1.getId());
        clan1.broadcastClanStatus();
        clan2.broadcastClanStatus();

        getDAO(ClanDAO.class).deleteClanWar(clanId1, clanId2);
    }

    private void restoreClanWars() {
        try (Connection con = DatabaseFactory.getInstance().getConnection();
             Statement statement = con.createStatement();
             ResultSet rset = statement.executeQuery("SELECT clan1, clan2, clan1Kill, clan2Kill, winnerClan, startTime, endTime, state FROM clan_wars")) {
            while (rset.next()) {
                final Clan attacker = getClan(rset.getInt("clan1"));
                final Clan attacked = getClan(rset.getInt("clan2"));
                if ((attacker != null) && (attacked != null)) {
                    final ClanWarState state = ClanWarState.values()[rset.getInt("state")];

                    final ClanWar clanWar = new ClanWar(attacker, attacked, rset.getInt("clan1Kill"), rset.getInt("clan2Kill"), rset.getInt("winnerClan"), rset.getLong("startTime"), rset.getLong("endTime"), state);
                    attacker.addWar(attacked.getId(), clanWar);
                    attacked.addWar(attacker.getId(), clanWar);
                } else {
                    LOGGER.warn(getClass().getSimpleName() + ": Restorewars one of clans is null attacker:" + attacker + " attacked:" + attacked);
                }
            }
        } catch (Exception e) {
            LOGGER.error(getClass().getSimpleName() + ": Error restoring clan wars data.", e);
        }
    }

    /**
     * Check for nonexistent alliances
     */
    private void allianceCheck() {
        for (Clan clan : clans.values()) {
            final int allyId = clan.getAllyId();
            if ((allyId != 0) && (clan.getId() != allyId) && !clans.containsKey(allyId)) {
                clan.setAllyId(0);
                clan.setAllyName(null);
                clan.changeAllyCrest(0, true);
                clan.updateClanInDB();
                LOGGER.info(getClass().getSimpleName() + ": Removed alliance from clan: " + clan);
            }
        }
    }

    public List<Clan> getClanAllies(int allianceId) {
        final List<Clan> clanAllies = new ArrayList<>();
        if (allianceId != 0) {
            for (Clan clan : clans.values()) {
                if ((clan != null) && (clan.getAllyId() == allianceId)) {
                    clanAllies.add(clan);
                }
            }
        }
        return clanAllies;
    }

    public void shutdown() {
        for (Clan clan : clans.values()) {
            clan.updateInDB();
            for (ClanWar war : clan.getWarList().values()) {
                storeClanWars(war);
            }
        }
    }

    public static void init() {
        getInstance().load();
    }

    public static ClanTable getInstance() {
        return Singleton.INSTANCE;
    }

    private static class Singleton {
        private static final ClanTable INSTANCE = new ClanTable();
    }
}
