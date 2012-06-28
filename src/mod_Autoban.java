
/**
 *
 * @author P@bloid
 */
import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;

public class mod_Autoban extends BaseMod
{

    Pattern chatLine = Pattern.compile("\\[G\\]\\s*([a-z0-9_]+):(.*)", Pattern.CASE_INSENSITIVE);
    Pattern enterPlayer = Pattern.compile("^([a-z0-9_]+)\\s.*", Pattern.CASE_INSENSITIVE);
    Pattern incorrectNickname = Pattern.compile("(\\w)\\1{3,}", Pattern.CASE_INSENSITIVE);
    Pattern probablyIncorrectNickname = Pattern.compile("\\D\\d\\D|^\\d+\\D", Pattern.CASE_INSENSITIVE);
    Properties config = null;
    afu onOffButton = new afu("ToggleAutoban", 64);
    afu banButton = new afu("Ban", 65);
    boolean enabled = false;
    private Map<String, Integer> capsers = new HashMap();
    private Map<String, Long> capsersTimestamps = new HashMap();
    private String incorrectName = null;
    private PrintWriter logger;

    @Override
    public String getVersion()
    {
        return "0.0.18e";
    }

    @Override
    public void load()
    {
        loadConfig();
        ModLoader.registerKey(this, this.onOffButton, false);
        ModLoader.addLocalization("ToggleAutoban", "Пeреключить Автобан");
        ModLoader.registerKey(this, this.banButton, false);
        ModLoader.addLocalization("Ban", "Банить");
        try
        {
            File f = new File(Minecraft.b(), "/Autoban.log");
            f.createNewFile();
            logger = new PrintWriter(f);
        }
        catch (IOException ex)
        {
            Logger.getLogger(mod_Autoban.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    private void loadConfig()
    {
        if (this.config == null)
            this.config = new Properties();
        try
        {
            this.config.clear();
            File fConf = new File(Minecraft.b(), "/config/Autoban.cfg");
            if (!fConf.canRead())
            {
                log("Error loading config, using default values");
                fConf.createNewFile();

                char[] buf = new char[1024];
                try (Reader reader = new InputStreamReader(getClass().getResourceAsStream("/Autoban.cfg"));
                     Writer writer = new OutputStreamWriter(new FileOutputStream(fConf), "windows-1251"))
                {
                    int len;
                    while ((len = reader.read(buf)) > 0)
                        writer.write(buf, 0, len);
                }
            }
            this.config.load(new FileReader(fConf));
            if (!Boolean.parseBoolean(this.config.getProperty("nodebug", "true")))
            {
                logLocal("&3minLength &6= &2" + this.config.getProperty("minLength", "5"));
                logLocal("&3maxPercentage &6= &2" + this.config.getProperty("maxPercentage", "75"));
                logLocal("&3cooldownTime &6= &2" + this.config.getProperty("cooldownTime", "5m"));
                logLocal("&3reloadConfigOnEnabling &6= &2" + this.config.getProperty("reloadConfigOnEnabling", "true"));
            }
            logLocal("&2Config successful loaded");
        }
        catch (IOException ex)
        {
            Logger.getLogger(mod_Autoban.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    @Override
    public void keyboardEvent(afu event)
    {
        if (this.onOffButton.equals(event))
            if (!this.enabled)
                enableAutoban();
            else
                disableAutoban();
        else if (this.banButton.equals(event))
            if (this.incorrectName != null)
            {
                sendFormattedCommand(this.incorrectName, "banCmd", "/ban %name% Incorrect nickname");
                this.incorrectName = null;
            }
            else
                logLocal(this.config.getProperty("nobodyMsg", "&cNobody to ban!"));
    }

    private void disableAutoban()
    {
        logLocal(this.config.getProperty("disabledMsg", "&cNow disabled"));
        this.enabled = false;
    }

    @Override
    public void receiveChatPacket(String s)
    {
        s = s.replaceAll("§[0-9a-f]", "");
        String playerName = ModLoader.getMinecraftInstance().k.b;
        if (s.contains("Moderator (mod) " + playerName))
        {
            boolean enableOnEnter = Boolean.parseBoolean(this.config.getProperty("enableOnEnter", "true"));
            if (enableOnEnter)
                enableAutoban();
        }
        if (!this.enabled)
            return;
        Matcher patPlayer = this.enterPlayer.matcher(s);
        Matcher patMessage = this.chatLine.matcher(s);

        if (patMessage.find())
        {
            String nick = patMessage.group(1);
            String message = patMessage.group(2);
            messageReceived(nick, message);
        }
        else if (patPlayer.find())
            checkCorrectNickname(patPlayer.group(1));
    }

    private void enableAutoban()
    {
        boolean reloadConfig = Boolean.parseBoolean(this.config.getProperty("reloadConfigOnEnabling", "true"));
        if (reloadConfig)
            loadConfig();
        logLocal(this.config.getProperty("enabledMsg", "&2Now enabled"));
        this.enabled = true;
        this.capsers.clear();
        this.capsersTimestamps.clear();
    }

    @Override
    public void serverConnect(adl handler)
    {
        this.enabled = false;
        logLocal(this.config.getProperty("loadedMsg", "&3Loaded"));
    }

    private void log(String s)
    {
        s = s.replaceAll("§[0-9a-f]", "");
        s = s.replaceAll("&[0-9a-f]", "");
        s = s.replace("[Autoban]", "");
        System.out.println("[Autoban] " + s);
        if (logger != null)
            logger.println("[Autoban] " + s);
    }

    private void logLocal(String s)
    {
        s = formatMessage("&6[Autoban] " + s);
        log(s);
        Minecraft mc = ModLoader.getMinecraftInstance();
        if (mc == null)
            return;
        aiy w = mc.w;
        if (w == null)
            return;
        w.a(s);
    }

    private void sendFormattedCommand(String victim, String propertyName, String defaultCmd)
    {
        String cmd = this.config.getProperty(propertyName, defaultCmd);
        if (cmd.contains("%name%"))
            cmd = cmd.replace("%name%", victim);
        sendMessage(cmd);
    }

    private void logFormattedMessage(String victim, String propertyName, String defaultCmd)
    {
        String msg = this.config.getProperty(propertyName, defaultCmd);
        if (msg.contains("%name%"))
            msg = msg.replace("%name%", victim);
        logLocal(msg);
    }

    private void sendMessage(String message)
    {
        ModLoader.sendPacket(new afd(message));
    }

    private void messageReceived(String victim, String message)
    {
        if (checkCorrectNickname(victim))
        {
            String uppercase = message.replaceAll("[^A-Z\\xC0-\\xDF]", "");
            int minLength = Integer.parseInt(this.config.getProperty("minLength", "5"));

            if (uppercase.length() >= minLength)
            {
                String original = message.replaceAll("[^A-Za-z\\xC0-\\xDF\\xE0-\\xFF]", "");

                int maxPercentage = Integer.parseInt(this.config.getProperty("maxPercentage", "75"));
                int percentage;
                if (original.length() == 0)
                    percentage = 0;
                else
                    percentage = 100 * uppercase.length() / original.length();
                if (percentage > maxPercentage)
                {
                    int curWarnings = 0;
                    long timestamp = 0L;
                    if (this.capsers.containsKey(victim))
                    {
                        curWarnings = ((Integer) this.capsers.get(victim)).intValue();
                        timestamp = ((Long) this.capsersTimestamps.get(victim)).longValue();
                    }
                    if (System.currentTimeMillis() - timestamp > getCooldownTime())
                        curWarnings = 0;
                    curWarnings++;
                    this.capsers.put(victim, Integer.valueOf(curWarnings));
                    this.capsersTimestamps.put(victim, Long.valueOf(System.currentTimeMillis()));
                    switch (curWarnings)
                    {
                        case 1:
                            sendFormattedCommand(victim, "firstCmd", "/tell %name% Turn off Caps Lock, please");
                            break;
                        case 2:
                            sendFormattedCommand(victim, "secondCmd", "/warn %name% Caps Lock");
                            break;
                        case 3:
                            sendFormattedCommand(victim, "thirdCmd", "/kick %name% Caps Lock");
                            break;
                        default:
                            sendFormattedCommand(victim, "lastCmd", "/tempban %name% 1 hour Caps Lock, all warning was ignored, 1 hour");
                            this.capsers.remove(victim);
                            this.capsersTimestamps.remove(victim);
                    }
                }
            }
        }
    }

    private String formatMessage(String s)
    {
        return s.replace('&', '§');
    }

    private boolean checkCorrectNickname(String name)
    {
        String digits = name.replaceAll("\\D", "");
        boolean incorrect = digits.length() > 4;
        boolean probablyIncorrect = this.probablyIncorrectNickname.matcher(name).matches();
        if (incorrect)
        {
            logFormattedMessage(name, "incorrectMsg", "Nickname &2%name%&6 is &cincorrect. &6Press ban button to ban");
            this.incorrectName = name;
        }
        else if (probablyIncorrect)
        {
            logFormattedMessage(name, "probablyIncorrectMsg", "Nickname &2%name%&6 is &3probably incorrect. &6Press ban button to ban");
            this.incorrectName = name;
        }
        return true;
    }

    private long getCooldownTime()
    {
        String cooldown = this.config.getProperty("cooldownTime", "5m");
        long cooldownTime = Integer.parseInt(cooldown.substring(0, cooldown.length() - 1));
        char timeMultiplier = cooldown.charAt(cooldown.length() - 1);
        switch (timeMultiplier)
        {
            case 'd':
                cooldownTime *= 24L;
            case 'h':
                cooldownTime *= 60L;
            case 'm':
                cooldownTime *= 60L;
        }
        cooldownTime *= 1000L;

        return cooldownTime;
    }
}
