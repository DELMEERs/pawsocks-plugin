package club.zeroiq.pawsocks.social;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWaypoint;

import java.util.UUID;

/**
 * Полоса локатора (1.21.6+): не отправляем игроку точки тех, кто ему не друг.
 * Загружается только когда на сервере есть packetevents.
 */
public final class LocatorFilter implements PacketListener {

    private final FriendsFeature friends;
    private PacketListenerCommon registered;

    private LocatorFilter(FriendsFeature friends) {
        this.friends = friends;
    }

    /** Подключить фильтр. Возвращает, как его отключить. */
    public static Runnable enable(FriendsFeature friends) {
        LocatorFilter f = new LocatorFilter(friends);
        f.registered = PacketEvents.getAPI().getEventManager().registerListener(f, PacketListenerPriority.NORMAL);
        return () -> PacketEvents.getAPI().getEventManager().unregisterListener(f.registered);
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.WAYPOINT) return;
        UUID receiver = event.getUser().getUUID();
        if (receiver == null) return;
        WrapperPlayServerWaypoint packet = new WrapperPlayServerWaypoint(event);
        if (packet.getOperation() == WrapperPlayServerWaypoint.Operation.UNTRACK) return;
        var id = packet.getWaypoint().getIdentifier();
        if (!id.isLeft()) return;
        UUID target = id.getLeft();
        if (target != null && !friends.visibleOnLocator(receiver, target)) event.setCancelled(true);
    }
}
