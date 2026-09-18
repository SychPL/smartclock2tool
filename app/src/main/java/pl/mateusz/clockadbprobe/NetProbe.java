package pl.mateusz.clockadbprobe;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/** Network info: Wi-Fi state, IP addresses, gateway, interface names. */
public final class NetProbe {

    public static String wifiIp = null;

    public static void probe(Context ctx, Report rep) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm != null ? cm.getActiveNetworkInfo() : null;
            rep.log("NETWORK", "Connected: " + (ni != null && ni.isConnected())
                    + (ni != null ? " type=" + ni.getTypeName() + " state=" + ni.getState() : " (no active network)"));
        } catch (Throwable t) {
            rep.exception("NetProbe.connectivity", t);
        }

        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                WifiInfo wi = wm.getConnectionInfo();
                if (wi != null) {
                    String ssid = wi.getSSID();
                    rep.log("NETWORK", "SSID: " + ("\"<unknown ssid>\"".equals(ssid) || ssid == null ? "NOT ACCESSIBLE FROM APP (needs location permission)" : ssid));
                    int ip = wi.getIpAddress();
                    if (ip != 0) {
                        wifiIp = String.format("%d.%d.%d.%d", ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >> 24) & 0xff);
                        rep.log("NETWORK", "Wi-Fi IPv4 (WifiManager): " + wifiIp);
                    }
                    int gw = wm.getDhcpInfo() != null ? wm.getDhcpInfo().gateway : 0;
                    if (gw != 0) {
                        rep.log("NETWORK", "Gateway: " + String.format("%d.%d.%d.%d", gw & 0xff, (gw >> 8) & 0xff, (gw >> 16) & 0xff, (gw >>> 24) & 0xff));
                    }
                }
            }
        } catch (Throwable t) {
            rep.exception("NetProbe.wifi", t);
        }

        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface nif = ifs.nextElement();
                if (!nif.isUp()) continue;
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                StringBuilder ips = new StringBuilder();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    ips.append(a.getHostAddress()).append(' ');
                    if (wifiIp == null && a instanceof Inet4Address && !a.isLoopbackAddress()
                            && (nif.getName().startsWith("wlan") || nif.getName().startsWith("eth"))) {
                        wifiIp = a.getHostAddress();
                    }
                }
                if (ips.length() > 0) rep.line("NETWORK", "iface " + nif.getName() + ": " + ips.toString().trim());
            }
        } catch (Throwable t) {
            rep.exception("NetProbe.interfaces", t);
        }
    }
}
