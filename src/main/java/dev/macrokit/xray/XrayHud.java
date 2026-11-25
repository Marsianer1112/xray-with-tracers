package dev.macrokit.xray;

import dev.macrokit.MacroKitClient;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * Zeichnet die Erz-Markierungen als Punkte über das Bild, auch durch Wände hindurch.
 * Die Position auf dem Bildschirm wird aus Kamera, Blickrichtung und FOV berechnet.
 */
public final class XrayHud {
	private static final int MAX_LABELS = 6;

	private XrayHud() {
	}

	public static void register() {
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
			Identifier.fromNamespaceAndPath(MacroKitClient.MOD_ID, "xray"), XrayHud::extract);
	}

	private static void extract(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		XraySettings s = XrayManager.settings();
		if (!s.enabled) {
			return;
		}
		XrayManager.Hit[] hits = XrayManager.visible();
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		if (hits.length == 0 || p == null) {
			return;
		}

		float partial = deltaTracker.getGameTimeDeltaPartialTick(false);
		Vec3 eye = p.getEyePosition(partial);
		double yaw = Math.toRadians(p.getYRot());
		double pitch = Math.toRadians(p.getXRot());
		double cy = Math.cos(yaw);
		double sy = Math.sin(yaw);
		double cp = Math.cos(pitch);
		double sp = Math.sin(pitch);
		// Blickrichtung, rechts und oben (Minecraft: Yaw 0 = Süden, positiver Pitch = nach unten)
		double fx = -sy * cp;
		double fy = -sp;
		double fz = cy * cp;
		double rx = -cy;
		double ry = 0;
		double rz = -sy;
		double ux = ry * fz - rz * fy;
		double uy = rz * fx - rx * fz;
		double uz = rx * fy - ry * fx;

		int w = mc.getWindow().getGuiScaledWidth();
		int h = mc.getWindow().getGuiScaledHeight();
		double tan = Math.tan(Math.toRadians(mc.options.fov().get()) / 2.0);
		double aspect = (double) w / (double) h;

		int labels = 0;
		for (XrayManager.Hit hit : hits) {
			double dx = hit.x() + 0.5 - eye.x;
			double dy = hit.y() + 0.5 - eye.y;
			double dz = hit.z() + 0.5 - eye.z;
			double depth = dx * fx + dy * fy + dz * fz;
			if (depth < 0.3) {
				continue; // hinter der Kamera
			}
			double nx = (dx * rx + dy * ry + dz * rz) / depth / (tan * aspect);
			double ny = (dx * ux + dy * uy + dz * uz) / depth / tan;
			if (Math.abs(nx) > 1.05 || Math.abs(ny) > 1.05) {
				continue;
			}
			int px = (int) ((nx + 1.0) * 0.5 * w);
			int py = (int) ((1.0 - ny) * 0.5 * h);

			g.fill(px - 3, py - 3, px + 4, py + 4, 0xFF000000);
			g.fill(px - 2, py - 2, px + 3, py + 3, hit.type().color);

			if (labels < MAX_LABELS) {
				labels++;
				double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
				g.text(mc.font, String.format(Locale.ROOT, "%.0fm", dist), px + 6, py - 4, hit.type().color, true);
			}
		}

		// Tracer: Linien von der Bildschirmmitte zu den nächsten Erzen (hits ist nach Entfernung sortiert)
		int tracers = Math.min(Math.max(s.tracerCount, 0), hits.length);
		double halfW = w / 2.0;
		double halfH = h / 2.0;
		for (int i = 0; i < tracers; i++) {
			XrayManager.Hit hit = hits[i];
			double dx = hit.x() + 0.5 - eye.x;
			double dy = hit.y() + 0.5 - eye.y;
			double dz = hit.z() + 0.5 - eye.z;
			double depth = dx * fx + dy * fy + dz * fz;
			double sideways = dx * rx + dy * ry + dz * rz;
			double upwards = dx * ux + dy * uy + dz * uz;
			double ex;
			double ey;
			if (depth > 0.05) {
				ex = halfW + sideways / depth / (tan * aspect) * halfW;
				ey = halfH - upwards / depth / tan * halfH;
			} else {
				// hinter oder neben dir: die Linie zeigt in die Richtung, in die du dich drehen musst
				double len = Math.sqrt(sideways * sideways + upwards * upwards);
				double toX = len < 1e-6 ? 0 : sideways / len;
				double toUp = len < 1e-6 ? -1 : upwards / len; // genau dahinter: nach unten
				ex = halfW + toX * 4000;
				ey = halfH - toUp * 4000;
			}
			line(g, halfW, halfH, ex, ey, w, h, (hit.type().color & 0x00FFFFFF) | 0xD0000000);
		}
	}

	/** Zeichnet eine Linie aus 1x1-Punkten von (x0, y0) Richtung (x1, y1), bis zum Ziel oder Bildschirmrand. */
	private static void line(GuiGraphicsExtractor g, double x0, double y0, double x1, double y1, int w, int h, int color) {
		double dx = x1 - x0;
		double dy = y1 - y0;
		double len = Math.sqrt(dx * dx + dy * dy);
		if (len < 8) {
			return;
		}
		double ux = dx / len;
		double uy = dy / len;
		int steps = (int) Math.min(len, 2000);
		for (int i = 5; i < steps; i++) { // die ersten Pixel frei lassen, damit das Fadenkreuz sichtbar bleibt
			int x = (int) (x0 + ux * i);
			int y = (int) (y0 + uy * i);
			if (x < 0 || y < 0 || x >= w || y >= h) {
				break;
			}
			g.fill(x, y, x + 1, y + 1, color);
		}
	}
}
