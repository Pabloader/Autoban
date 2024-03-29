
/**
 *
 * @author P@bloid
 */
import java.io.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;

public class mod_Autoban extends BaseMod
{

    Pattern chatLine = Pattern.compile("\\[G\\]\\s*([a-z0-9_]+):\\s*(.*)", Pattern.CASE_INSENSITIVE);
    Pattern enterPlayer = Pattern.compile("^([a-z0-9_]+)\\s.*", Pattern.CASE_INSENSITIVE);
    Pattern incorrectNickname = Pattern.compile("(\\w)\\1{3,}", Pattern.CASE_INSENSITIVE);
    Pattern probablyIncorrectNickname = Pattern.compile("\\D\\d\\D+\\d\\D|^\\d+\\D", Pattern.CASE_INSENSITIVE);
    Pattern death = Pattern.compile("([a-z0-9_]+) was (?:slain|shot) by ([a-z0-9_]+)", Pattern.CASE_INSENSITIVE);
    Pattern logLine = Pattern.compile("([a-z0-9_]+) destroyed ([\\w\\s]+) at ([-\\d]{1,5}):([-\\d]{1,5}):([-\\d]{1,5})", Pattern.CASE_INSENSITIVE);
    Pattern logPage = Pattern.compile("page (\\d+)/(\\d+)", Pattern.CASE_INSENSITIVE);
    Properties config = null;
    afu rollbackButton = new afu("RollBack", 62);
    afu onOffButton = new afu("ToggleAutoban", 64);
    afu banButton = new afu("Ban", 65);
    afu ignoreButton = new afu("Ignore", 70);
    boolean enabled = false;
    private Map<String, Integer> capsers = new HashMap();
    private Map<String, Long> capsersTimestamps = new HashMap();
    private String incorrectName = null;
    private OutputStream logger;
    private Set<String> ignored = new HashSet<>();
    private Writer ignoreWriter;
    private Writer deathWriter;
    private Map<Point3d, Integer> rolled = new HashMap<>();
    private boolean rollback = false;

    @Override
    public String getVersion()
    {
        return "0.0.22";
    }

    @Override
    public void load()
    {
        loadConfig();
        ModLoader.registerKey(this, rollbackButton, false);
        ModLoader.addLocalization("RollBack", "Откатить");
        ModLoader.registerKey(this, onOffButton, false);
        ModLoader.addLocalization("ToggleAutoban", "Пeреключить Автобан");
        ModLoader.registerKey(this, banButton, false);
        ModLoader.addLocalization("Ban", "Банить");
        ModLoader.registerKey(this, ignoreButton, false);
        ModLoader.addLocalization("Ignore", "Игнорировать");
        ModLoader.setInGameHook(this, true, true);
        try
        {
            File ign = new File(getConfigDirectory(), "ignored.txt");
            File f = new File(getConfigDirectory(), "Autoban.log");
            if (!f.exists())
                f.createNewFile();
            File deaths = new File(getConfigDirectory(), "deaths.log");
            if (!deaths.exists())
                deaths.createNewFile();
            deathWriter = new FileWriter(deaths, true);
            logger = new FileOutputStream(f, true);
            if (!ign.exists())
                ign.createNewFile();
            try (BufferedReader br = new BufferedReader(new FileReader(ign)))
            {
                String line;
                while ((line = br.readLine()) != null)
                    ignored.add(line.trim().toLowerCase());
            }
            ignoreWriter = new FileWriter(ign, true);
        }
        catch (IOException ex)
        {
            Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, ex);
        }
    }

    private File getConfigDirectory()
    {
        File dir = new File(Minecraft.b(), "/mods/Autoban/");
        if (!dir.exists())
            dir.mkdirs();
        return dir;
    }

    @Override
    public boolean onTickInGame(float tick, Minecraft game)
    {
        return true;
    }

    public void setBlock(Point3d p, int blockType)
    {
        yw player = ModLoader.getMinecraftInstance().h;
        player.k.d(p.x, p.y, p.z, blockType);
    }

    public int getBlock(Point3d p)
    {
        yw player = ModLoader.getMinecraftInstance().h;
        return player.k.a(p.x, p.y, p.z);
    }

    private void ignore(String ignor)
    {
        ignor = ignor.toLowerCase();
        ignored.add(ignor);
        if (ignoreWriter != null)
            try
            {
                ignoreWriter.append(ignor).append('\n');
                ignoreWriter.flush();
            }
            catch (IOException ex)
            {
                Logger.getLogger(mod_Autoban.class.getName()).log(Level.SEVERE, null, ex);
            }
    }

    private void loadConfig()
    {
        if (config == null)
            config = new Properties();
        try
        {
            config.clear();
            File fConf = new File(getConfigDirectory(), "Autoban.cfg");
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
            config.load(new FileReader(fConf));
            if (!Boolean.parseBoolean(config.getProperty("nodebug", "true")))
            {
                logLocal("&3minLength &6= &2" + config.getProperty("minLength", "5"));
                logLocal("&3maxPercentage &6= &2" + config.getProperty("maxPercentage", "75"));
                logLocal("&3cooldownTime &6= &2" + config.getProperty("cooldownTime", "5m"));
                logLocal("&3reloadConfigOnEnabling &6= &2" + config.getProperty("reloadConfigOnEnabling", "true"));
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
        if (onOffButton.equals(event))
            if (!enabled)
                enableAutoban();
            else
                disableAutoban();
        else if (banButton.equals(event))
            if (incorrectName != null && !ignored.contains(incorrectName.toLowerCase()))
            {
                sendFormattedCommand(incorrectName, "banCmd", "/ban %name% Incorrect nickname");
                ignore(incorrectName);
                incorrectName = null;
            }
            else
                logLocal(config.getProperty("nobodyMsg", "&cNobody to ban!"));
        else if (ignoreButton.equals(event))
        {
            if (incorrectName != null && !ignored.contains(incorrectName.toLowerCase()))
            {
                ignore(incorrectName);
                logLocal(incorrectName + " &6ignored");
                incorrectName = null;
            }
        }
        else if (rollbackButton.equals(event))
            if (!rollback)
            {
                rollback = true;
                logLocal(config.getProperty("rollbackEnabledMsg", "&2Rollback enabled"));
            }
            else
            {
                rollback = false;
                logLocal(config.getProperty("rollbackDisabledMsg", "&cRollback disabled"));
                for (Map.Entry<Point3d, Integer> entry : rolled.entrySet())
                    setBlock(entry.getKey(), entry.getValue());
                rolled.clear();
            }
    }

    private void disableAutoban()
    {
        logLocal(config.getProperty("disabledMsg", "&cNow disabled"));
        enabled = false;
    }

    @Override
    public void receiveChatPacket(String s)
    {
        s = s.replaceAll("§[0-9a-f]", "");
        String playerName = ModLoader.getMinecraftInstance().k.b;
        if (s.contains("Moderator (mod) " + playerName))
        {
            boolean enableOnEnter = Boolean.parseBoolean(config.getProperty("enableOnEnter", "true"));
            if (enableOnEnter)
                enableAutoban();
        }
        if (!enabled)
            return;
        final String line = s;
        final Matcher patPlayer = enterPlayer.matcher(s);
        final Matcher patMessage = chatLine.matcher(s);
        final Matcher patDeath = death.matcher(s);
        final Matcher patLog = logLine.matcher(s);
        final Matcher patPage = logPage.matcher(s);
        new Thread()
        {

            @Override
            public void run()
            {
                if (patMessage.matches())
                {
                    String nick = patMessage.group(1);
                    String message = patMessage.group(2);
                    messageReceived(nick, message);
                    logFile(line);
                }
                else if (rollback && patPage.find())
                {
                    int cur = Integer.parseInt(patPage.group(1));
                    int all = Integer.parseInt(patPage.group(2));
                    if (cur != all)
                        sendFormattedCommand("", "nextCmd", "/lb next");

                }
                else if (patDeath.matches())
                {
                    String killer = patDeath.group(2);
                    switch (killer.toLowerCase())
                    {
                        case "skeleton":
                        case "zombie":
                        case "spider":
                        case "blaze":
                        case "enderman":
                            return;
                    }
                    try
                    {
                        deathWriter.append('[').append(new Date().toString()).append(']').append(line).append("\n");
                        deathWriter.flush();
                    }
                    catch (IOException ex)
                    {
                    }

                }
                else if (rollback && patLog.find())
                {
                    int x = Integer.parseInt(patLog.group(3));
                    int y = Integer.parseInt(patLog.group(4));
                    int z = Integer.parseInt(patLog.group(5));
                    Point3d p3d = new Point3d(x, y, z);
                    if (rolled.containsKey(p3d))
                        return;
                    else
                        rolled.put(p3d, getBlock(p3d));
                    String block = patLog.group(2);
                    Material mat = Material.matchMaterial(block);
                    setBlock(p3d, mat.getId());
                }
                else if (patPlayer.matches())
                    checkCorrectNickname(patPlayer.group(1));
            }
        }.start();
    }

    private void enableAutoban()
    {
        boolean reloadConfig = Boolean.parseBoolean(config.getProperty("reloadConfigOnEnabling", "true"));
        if (reloadConfig)
            loadConfig();
        logLocal(config.getProperty("enabledMsg", "&2Now enabled"));
        enabled = true;
        capsers.clear();
        capsersTimestamps.clear();
    }

    @Override
    public void serverConnect(adl handler)
    {
        enabled = false;
        logLocal(config.getProperty("loadedMsg", "&3Loaded"));
    }

    private void log(String s)
    {
        s = s.replaceAll("[§&][0-9a-f]", "");
        s = s.replaceAll("\\[Autoban\\]\\s*", "");
        System.out.println("[Autoban] " + s);
        logFile(s);
    }

    private void logFile(String s)
    {
        //TODO Параметр в конфиге, запрещающий лог
        try
        {
            if (logger != null)
            {
                String message = "[" + new Date() + "] " + s + "\n";
                char[] cmess = message.toCharArray();
                byte[] bmess = message.getBytes();
                for (int i = 0; i < bmess.length; i++)
                    if (bmess[i] == '?')
                        logger.write(message.charAt(i));
                    else
                        logger.write(bmess[i]);
                logger.flush();
            }
        }
        catch (Exception ex)
        {
            Logger.getLogger(mod_Autoban.class.getName()).log(Level.SEVERE, null, ex);
        }
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
        String cmd = config.getProperty(propertyName, defaultCmd);
        if (cmd.contains("%name%"))
            cmd = cmd.replace("%name%", victim);
        sendMessage(cmd);
    }

    private void logFormattedMessage(String victim, String propertyName, String defaultCmd)
    {
        String msg = config.getProperty(propertyName, defaultCmd);
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
        logFile("Message: '" + message + "' from " + victim);
        checkCorrectNickname(victim);

        String uppercase = message.replaceAll("[^A-Z\\xC0-\\xDF]", "");
        logFile("Uppercase: '" + uppercase + "'");
        int minLength = Integer.parseInt(config.getProperty("minLength", "5"));

        if (uppercase.length() >= minLength)
        {
            String original = message.replaceAll("[^A-Za-z\\xC0-\\xDF\\xE0-\\xFF]", "");

            logFile("Original: '" + original + "'");
            int maxPercentage = Integer.parseInt(config.getProperty("maxPercentage", "75"));
            int percentage;
            if (original.length() == 0)
                percentage = 0;
            else
                percentage = 100 * uppercase.length() / original.length();
            logFile("Percentage: " + percentage + "%");
            if (percentage > maxPercentage)
            {
                int curWarnings = 0;
                long timestamp = 0L;
                if (capsers.containsKey(victim))
                {
                    curWarnings = capsers.get(victim);
                    timestamp = capsersTimestamps.get(victim);
                }
                if (System.currentTimeMillis() - timestamp > getCooldownTime())
                    curWarnings = 0;
                curWarnings++;
                capsers.put(victim, curWarnings);
                capsersTimestamps.put(victim, System.currentTimeMillis());
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
                        capsers.remove(victim);
                        capsersTimestamps.remove(victim);
                }
            }
        }
    }

    private String formatMessage(String s)
    {
        return s.replace('&', '§');
    }

    private void checkCorrectNickname(String name)
    {
        String digits = name.replaceAll("\\D", "");
        boolean incorrect = digits.length() > 4;
        incorrect |= incorrectNickname.matcher(name).find();
        boolean probablyIncorrect = probablyIncorrectNickname.matcher(name).find();
        if (ignored.contains(name.toLowerCase()))
            return;
        if (incorrect)
        {
            logFormattedMessage(name, "incorrectMsg", "Nickname &2%name%&6 is &cincorrect. &6Press ban button to ban");
            incorrectName = name;
        }
        else if (probablyIncorrect)
        {
            logFormattedMessage(name, "probablyIncorrectMsg", "Nickname &2%name%&6 is &3probably incorrect. &6Press ban button to ban");
            incorrectName = name;
        }
        else
            logFile("Nickname " + name + " is correct");
    }

    private long getCooldownTime()
    {
        String cooldown = config.getProperty("cooldownTime", "5m");
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
