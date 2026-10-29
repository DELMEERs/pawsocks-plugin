package club.zeroiq.pawsocks.feature;

import org.bukkit.Color;
import org.bukkit.Material;

/** Цвета следов лапок. Названия берутся из messages.yml → trails.names.<id>. */
public enum Trail {
    PINK("pink", false, Material.PINK_DYE, 0xE6457F),
    LIGHT_PINK("light_pink", false, Material.PINK_WOOL, 0xFF9CC2),
    WHITE("white", false, Material.WHITE_DYE, 0xF4F1FA),
    SKY("sky", false, Material.LIGHT_BLUE_DYE, 0x7CC4FF),
    LAVENDER("lavender", true, Material.PURPLE_DYE, 0xB79CFF),
    MINT("mint", true, Material.LIME_DYE, 0x9BE38A),
    GOLD("gold", true, Material.YELLOW_DYE, 0xFFD166),
    MIDNIGHT("midnight", true, Material.BLUE_DYE, 0x3A4F95),
    RAINBOW("rainbow", true, Material.PRISMARINE_CRYSTALS, 0xE40303, 0xFF8C00, 0xFFED00, 0x008026, 0x004DFF, 0x750787),
    TRANS("trans", true, Material.LIGHT_BLUE_STAINED_GLASS, 0x5BCEFA, 0xF5A9B8, 0xFFFFFF, 0xF5A9B8),
    FEMBOY("femboy", true, Material.PINK_STAINED_GLASS, 0xD260A5, 0xE4AFCD, 0xFEFEFE, 0x57CEF8, 0x4F87E0);

    public final String id;
    public final boolean premium;
    public final Material icon;
    private final Color[] colors;

    Trail(String id, boolean premium, Material icon, int... rgb) {
        this.id = id;
        this.premium = premium;
        this.icon = icon;
        this.colors = new Color[rgb.length];
        for (int i = 0; i < rgb.length; i++) {
            colors[i] = Color.fromRGB(rgb[i]);
        }
    }

    /** Цвет отпечатка номер step: у флагов цвета идут по очереди. */
    public Color color(long step) {
        return colors[(int) Math.floorMod(step, (long) colors.length)];
    }

    public static Trail byId(String id) {
        if (id == null) return null;
        for (Trail t : values()) {
            if (t.id.equals(id)) return t;
        }
        return null;
    }
}
