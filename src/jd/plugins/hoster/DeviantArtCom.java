//    jDownloader - Downloadmanager
//    Copyright (C) 2012  JD-Team support@jdownloader.org
//
//    This program is free software: you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation, either version 3 of the License, or
//    (at your option) any later version.
//
//    This program is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//    GNU General Public License for more details.
//
//    You should have received a copy of the GNU General Public License
//    along with this program.  If not, see <http://www.gnu.org/licenses/>.

package jd.plugins.hoster;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import jd.PluginWrapper;
import jd.config.ConfigContainer;
import jd.config.ConfigEntry;
import jd.config.Property;
import jd.controlling.AccountController;
import jd.http.Browser;
import jd.http.Cookie;
import jd.http.Cookies;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.parser.html.Form;
import jd.plugins.Account;
import jd.plugins.AccountInfo;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.utils.locale.JDL;

import org.appwork.utils.formatter.SizeFormatter;

@HostPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "deviantart.com" }, urls = { "https?://[\\w\\.\\-]*?deviantart\\.com/art/[\\w\\-]+|https?://[\\w\\.\\-]*?deviantartdecrypted\\.com/journal/[\\w\\-]+" }, flags = { 2 })
public class DeviantArtCom extends PluginForHost {

    private String              DLLINK                       = null;
    private final String        COOKIE_HOST                  = "http://www.deviantart.com";
    private static final String NICE_HOST                    = "deviantart.com";
    private static final String NICE_HOSTproperty            = "deviantartcom";
    private final String        INVALIDLINKS                 = "https?://(www\\.)?forum\\.deviantart\\.com/art/general";
    private final String        MATURECONTENTFILTER          = ">Mature Content Filter<";
    private static Object       LOCK                         = new Object();
    private static final String FASTLINKCHECK_2              = "FASTLINKCHECK_2";
    private static final String FORCEHTMLDOWNLOAD            = "FORCEHTMLDOWNLOAD";

    private static final String GENERALFILENAMEREGEX         = "<title>([^<>\"]*?) on deviantART</title>";
    private static final String DLLINK_REFRESH_NEEDED        = "http://(www\\.)?deviantart\\.com/download/.+";
    private static final String TYPE_DOWNLOADALLOWED_PDF     = ">Download PDF<";
    private static final String TYPE_DOWNLOADALLOWED_TXT     = ">TXT download<";
    private static final String TYPE_DOWNLOADALLOWED_ZIP     = ">ZIP download<";
    private static final String TYPE_DOWNLOADALLOWED_GENERAL = ">Download File<";
    private static final String TYPE_DOWNLOADALLOWED_HTML    = "class=\"text\">HTML download</span>";
    private static final String TYPE_DOWNLOADFORBIDDEN_HTML  = "<div class=\"grf\\-indent\"";
    private static final String TYPE_DOWNLOADFORBIDDEN_SWF   = "class=\"flashtime\"";
    private static final String TYPE_ACCOUNTNEEDED           = "has limited the viewing of this artwork<";
    private boolean             HTMLALLOWED                  = false;

    private static final String LINKTYPE_ART                 = "https?://[\\w\\.\\-]*?deviantart\\.com/art/[^<>\"/]+";
    private static final String LINKTYPE_JOURNAL             = "https?://[\\w\\.\\-]*?deviantart\\.com/journal/[\\w\\-]+";

    /**
     * @author raztoki
     */
    public DeviantArtCom(PluginWrapper wrapper) {
        super(wrapper);
        this.setConfigElements();
        this.enablePremium(COOKIE_HOST.replace("http://", "https://") + "/join/");
    }

    @Override
    public String getAGBLink() {
        return COOKIE_HOST + "/";
    }

    @Override
    public void correctDownloadLink(final DownloadLink link) throws Exception {
        link.setUrlDownload(link.getDownloadURL().replace("deviantartdecrypted.com/", "deviantart.com/"));
    }

    /**
     * JD2 CODE. DO NOT USE OVERRIDE FOR JD=) COMPATIBILITY REASONS!
     */
    public boolean isProxyRotationEnabledForLinkChecker() {
        return false;
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws IOException, PluginException {
        this.setBrowserExclusive();
        if (link.getDownloadURL().matches(INVALIDLINKS)) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        // Workaround for a strange bug - DLLINK is not null at the beginning so if multiple links are to be checked they will all get the
        // same filenames
        DLLINK = null;
        br.setFollowRedirects(true);
        boolean loggedIn = false;
        final Account acc = AccountController.getInstance().getValidAccount(this);
        if (acc != null) {
            try {
                login(acc, this.br, false);
                loggedIn = true;
            } catch (final Exception e) {
            }
        }
        br.getPage(link.getDownloadURL());
        if (br.containsHTML("/error\\-title\\-oops\\.png\\)")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        // Motionbooks are not supported (yet)
        if (br.containsHTML(",target: \\'motionbooks/")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        String filename = br.getRegex(GENERALFILENAMEREGEX).getMatch(0);
        if (filename == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        filename = Encoding.htmlDecode(filename.trim());
        String ext = null;
        String filesize = null;
        if (this.getPluginConfig().getBooleanProperty(FORCEHTMLDOWNLOAD, false) || link.getDownloadURL().matches(LINKTYPE_JOURNAL)) {
            HTMLALLOWED = true;
            DLLINK = br.getURL();
            filename = findServerFilename(filename);
            ext = "html";
        } else if (br.containsHTML(TYPE_DOWNLOADALLOWED_PDF)) {
            ext = "pdf";
            /*
             * Even though there is an official pdf download link for browsers which have no embedded pdf support, it won't work so we'll
             * use the pdf viewer link which is basically the same
             */
            // DLLINK = getDOWNLOADdownloadlink();
            DLLINK = br.getRegex("new PDFObject\\(\\{url: \\'(https?://[^<>\"]*?)\\'\\}").getMatch(0);
            if (DLLINK == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            DLLINK = Encoding.htmlDecode(DLLINK.trim());
            filesize = getfileSize();
        } else if (br.containsHTML(TYPE_DOWNLOADALLOWED_TXT)) {
            ext = "txt";
            DLLINK = getDOWNLOADdownloadlink();
            if (DLLINK == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            DLLINK = Encoding.htmlDecode(DLLINK.trim());
        } else if (br.containsHTML(TYPE_DOWNLOADALLOWED_ZIP)) {
            ext = "zip";
            DLLINK = getDOWNLOADdownloadlink();
            if (DLLINK == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            DLLINK = Encoding.htmlDecode(DLLINK.trim());
        } else if (br.containsHTML(TYPE_DOWNLOADALLOWED_GENERAL)) {
            /* Download for other extensions */
            final Regex fInfo = br.getRegex("<strong>Download File</strong><br/>[\t\n\r ]+<small>([A-Za-z0-9]{1,5}), ([^<>\"]*?)</small>");
            ext = fInfo.getMatch(0);
            filesize = fInfo.getMatch(1);
            DLLINK = getDOWNLOADdownloadlink();
            if (ext == null || DLLINK == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            DLLINK = Encoding.htmlDecode(DLLINK.trim());
        } else if (br.containsHTML(TYPE_DOWNLOADFORBIDDEN_SWF)) {
            filesize = getImageSize();
            final String swf_sandbox = br.getRegex("class=\"flashtime\" src=\"(https?://sandbox\\.deviantart\\.com[^<>\"]*?)\"").getMatch(0);
            if (swf_sandbox == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            br.getPage(swf_sandbox);
            this.DLLINK = br.getRegex("<param name=\"movie\" value=\"(https?://[^<>\"]*?\\.swf)\"").getMatch(0);
            filename = findServerFilename(filename);
            ext = "swf";
        } else if (br.containsHTML(TYPE_DOWNLOADALLOWED_HTML)) {
            HTMLALLOWED = true;
            filename = findServerFilename(filename);
            ext = "html";
            filesize = getfileSize();
        } else if (br.containsHTML(TYPE_DOWNLOADFORBIDDEN_HTML)) {
            HTMLALLOWED = true;
            // Download whole html site
            DLLINK = br.getURL();
            filename = findServerFilename(filename);
            filesize = getfileSize();
            ext = "html";
            if (br.containsHTML(MATURECONTENTFILTER) && !loggedIn) {
                link.getLinkStatus().setStatusText("Mature content can only be downloaded via account");
                link.setName(filename + "." + ext);
                if (filesize != null) {
                    link.setDownloadSize(SizeFormatter.getSize(filesize.replace(",", "")));
                }
                return AvailableStatus.TRUE;
            }
        } else if (br.containsHTML(TYPE_ACCOUNTNEEDED)) {
            /* Account needed to view/download */
            filename = findServerFilename(filename);
            filesize = getfileSize();
            ext = "html";
        } else {
            filesize = getImageSize();
            // Maybe its a video
            if (filesize == null) {
                filesize = getfileSize();
            }

            if (br.containsHTML(MATURECONTENTFILTER) && !loggedIn) {
                link.getLinkStatus().setStatusText("Mature content can only be downloaded via account");
                link.setName(filename);
                if (filesize != null) {
                    link.setDownloadSize(SizeFormatter.getSize(filesize.replace(",", "")));
                }
                return AvailableStatus.TRUE;
            }

            ext = br.getRegex("<strong>Download Image</strong><br><small>([A-Za-z0-9]{1,5}),").getMatch(0);
            if (ext == null) {
                ext = new Regex(filename, "\\.([A-Za-z0-9]{1,5})$").getMatch(0);
            }
            filename = findServerFilename(filename);
            if (ext == null || ext.length() > 5) {
                final String dllink = getCrippledDllink();
                if (dllink != null) {
                    ext = dllink.substring(dllink.lastIndexOf(".") + 1);
                }
            }
            if (ext == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        }
        if (filesize != null) {
            link.setDownloadSize(SizeFormatter.getSize(filesize.replace(",", "")));
        } else {
            final Browser br2 = br.cloneBrowser();
            /* Workaround for old downloadcore bug that can lead to incomplete files */
            br2.getHeaders().put("Accept-Encoding", "identity");
            URLConnectionAdapter con = null;
            try {
                con = br2.openGetConnection(getDllink());
                if (con.getContentType().contains("html") && !HTMLALLOWED) {
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                } else {
                    link.setDownloadSize(con.getLongContentLength());
                }
            } finally {
                try {
                    con.disconnect();
                } catch (Throwable e) {
                }
            }
        }
        ext = ext.toLowerCase();
        if (!filename.endsWith(ext)) {
            filename += "." + ext.trim();
        }
        link.setFinalFileName(Encoding.htmlDecode(filename.trim()));
        return AvailableStatus.TRUE;
    }

    private String getDOWNLOADdownloadlink() {
        return br.getRegex("\"(http://(www\\.)?deviantart\\.com/download/[^<>\"]*?)\"").getMatch(0);
    }

    private String getfileSize() {
        String filesize = br.getRegex("<label>File Size:</label>([^<>\"]*?)<br/>").getMatch(0);
        if (filesize == null) {
            filesize = br.getRegex("<dt>File Size</dt><dd>([^<>\"]*?)</dd>").getMatch(0);
        }
        return filesize;
    }

    private String getImageSize() {
        String imagesize = br.getRegex("<label>Image Size:</label>([^<>\"]*?)<br>").getMatch(0);
        if (imagesize == null) {
            imagesize = br.getRegex("<dt>Image Size</dt><dd>([^<>\"]*?)</dd>").getMatch(0);
        }
        return imagesize;
    }

    private String getCrippledDllink() {
        String crippleddllink = null;
        try {
            final String linkWithExt = getDllink();
            final String toRemove = new Regex(linkWithExt, "(\\?token=.+)").getMatch(0);
            if (toRemove != null) {
                crippleddllink = linkWithExt.replace(toRemove, "");
            } else {
                crippleddllink = linkWithExt;
            }
        } catch (final Exception e) {
        }
        return crippleddllink;
    }

    private String findServerFilename(final String oldfilename) {
        // Try to get server filename, if not possible, return old one
        String newfilename = null;
        final String dllink = getCrippledDllink();
        if (dllink != null) {
            newfilename = new Regex(dllink, "/([^<>\"/]+)$").getMatch(0);
        } else {
            newfilename = oldfilename;
        }
        return newfilename;
    }

    @Override
    public void handleFree(final DownloadLink downloadLink) throws Exception, PluginException {
        requestFileInformation(downloadLink);
        if (br.containsHTML(TYPE_ACCOUNTNEEDED)) {
            try {
                throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_ONLY);
            } catch (final Throwable e) {
                if (e instanceof PluginException) {
                    throw (PluginException) e;
                }
            }
            throw new PluginException(LinkStatus.ERROR_FATAL, "This content can only be downloaded via account");
        } else if (br.containsHTML(MATURECONTENTFILTER)) {
            try {
                throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_ONLY);
            } catch (final Throwable e) {
                if (e instanceof PluginException) {
                    throw (PluginException) e;
                }
            }
            throw new PluginException(LinkStatus.ERROR_FATAL, "Mature content can only be downloaded via account");
        }
        if (DLLINK == null) {
            getDllink();
        }
        /* Workaround for old downloadcore bug that can lead to incomplete files */
        /* Disable chunks as we only download pictures or small files */
        br.getHeaders().put("Accept-Encoding", "identity");
        dl = jd.plugins.BrowserAdapter.openDownload(br, downloadLink, DLLINK, true, 1);
        if (dl.getConnection().getContentType().contains("html") && !HTMLALLOWED) {
            handleServerErrors(downloadLink);
            br.followConnection();
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl.startDownload();
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return -1;
    }

    @Override
    public void handlePremium(final DownloadLink downloadLink, final Account account) throws Exception {
        /* This will also log in */
        requestFileInformation(downloadLink);
        if (DLLINK == null) {
            getDllink();
        }
        /* Workaround for old downloadcore bug that can lead to incomplete files */
        br.getHeaders().put("Accept-Encoding", "identity");
        /* Disable chunks as we only download pictures */
        dl = jd.plugins.BrowserAdapter.openDownload(br, downloadLink, DLLINK, true, 1);
        if (dl.getConnection().getContentType().contains("html") && !HTMLALLOWED) {
            handleServerErrors(downloadLink);
            br.followConnection();
            if (br.containsHTML("><title>Redirection</title>")) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error: unknown redirect", 5 * 60 * 1000l);
            }
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl.startDownload();
    }

    private void handleServerErrors(final DownloadLink dlink) throws PluginException {
        if (dl.getConnection().getResponseCode() == 404) {
            logger.info(NICE_HOST + ": 404servererror");
            int timesFailed = dlink.getIntegerProperty(NICE_HOSTproperty + "timesfailed_404servererror", 0);
            dlink.getLinkStatus().setRetryCount(0);
            if (timesFailed <= 2) {
                timesFailed++;
                dlink.setProperty(NICE_HOSTproperty + "timesfailed_404servererror", timesFailed);
                logger.info(NICE_HOST + ": timesfailed_404servererror -> Retrying in 30 minutes");
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 404", 30 * 60 * 1000l);
            } else {
                dlink.setProperty(NICE_HOSTproperty + "timesfailed_404servererror", Property.NULL);
                logger.info(NICE_HOST + ": timesfailed_404servererror - Download must be broken!");
                throw new PluginException(LinkStatus.ERROR_FATAL, "Download broken (deviantart server issue 404)!");
            }

        }
    }

    private String getDllink() throws PluginException {
        if (DLLINK == null) {
            String dllink = null;
            // Check if it's a video
            dllink = br.getRegex("\"src\":\"(http:[^<>\"]*?mp4)\"").getMatch(0);
            // First try to get downloadlink, if that doesn't exist, try to get the link to the picture which is displayed in browser
            if (dllink == null) {
                dllink = br.getRegex("\"(http://(www\\.)?deviantart\\.com/download/[^<>\"]*?)\"").getMatch(0);
            }
            if (dllink == null) {
                if (br.containsHTML(">Mature Content</span>")) {
                    /* Prefer HQ */
                    dllink = getHQpic();
                    if (dllink == null) {
                        dllink = br.getRegex("data\\-gmiclass=\"ResViewSizer_img\".*?src=\"(http://[^<>\"]*?)\"").getMatch(0);
                    }
                    if (dllink == null) {
                        dllink = br.getRegex("<img collect_rid=\"\\d+:\\d+\" src=\"(https?://[^\"]+)").getMatch(0);
                    }
                } else {
                    /* Prefer HQ */
                    dllink = getHQpic();
                    if (dllink == null) {
                        dllink = br.getRegex("(name|property)=\"og:image\" content=\"(http://[^<>\"]*?)\"").getMatch(1);
                    }
                    if (dllink == null) {
                        dllink = br.getRegex("<div class=\"dev\\-view\\-deviation\">[\t\n\r ]+<img collect_rid=\"[0-9:]+\" src=\"(http[^<>\"]*?)\"").getMatch(0);
                    }
                }
            }
            if (dllink == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            dllink = dllink.replace("\\", "");
            dllink = Encoding.htmlDecode(dllink);
            DLLINK = dllink;
        }
        return DLLINK;
    }

    private String getHQpic() {
        return br.getRegex("class=\"dev\\-content\\-normal\">[\t\n\r ]+<img collect_rid=\"[0-9:]+\" src=\"(http://[^<>\"]*?)\"").getMatch(0);
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return -1;
    }

    @Override
    public AccountInfo fetchAccountInfo(Account account) throws Exception {
        AccountInfo ai = new AccountInfo();
        try {
            login(account, this.br, true);
        } catch (PluginException e) {
            account.setValid(false);
            return ai;
        }
        account.setValid(true);
        ai.setStatus("Free Registered User");
        return ai;
    }

    @SuppressWarnings("unchecked")
    public void login(final Account account, final Browser br, final boolean force) throws Exception {
        synchronized (LOCK) {
            try {
                /** Load cookies */
                br.setCookiesExclusive(true);
                final Object ret = account.getProperty("cookies", null);
                boolean acmatch = Encoding.urlEncode(account.getUser()).equals(account.getStringProperty("name", Encoding.urlEncode(account.getUser())));
                if (acmatch) {
                    acmatch = Encoding.urlEncode(account.getPass()).equals(account.getStringProperty("pass", Encoding.urlEncode(account.getPass())));
                }
                if (acmatch && ret != null && ret instanceof HashMap<?, ?> && !force) {
                    final HashMap<String, String> cookies = (HashMap<String, String>) ret;
                    if (account.isValid()) {
                        for (final Map.Entry<String, String> cookieEntry : cookies.entrySet()) {
                            final String key = cookieEntry.getKey();
                            final String value = cookieEntry.getValue();
                            br.setCookie(this.getHost(), key, value);
                        }
                        return;
                    }
                }
                br.setCookie(this.getHost(), "lang", "english");
                br.getPage("https://www.deviantart.com/users/login");
                Form loginform = br.getFormbyProperty("id", "login");
                if (loginform == null) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                loginform.put("username", Encoding.urlEncode(account.getUser()));
                loginform.put("password", Encoding.urlEncode(account.getPass()));
                loginform.put("remember_me", "1");
                br.submitForm(loginform);
                if (br.getRedirectLocation() != null) {
                    if (br.getRedirectLocation().contains("deviantart.com/users/wrong-password?")) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                }
                /** Save cookies */
                final HashMap<String, String> cookies = new HashMap<String, String>();
                final Cookies add = br.getCookies(this.getHost());
                for (final Cookie c : add.getCookies()) {
                    cookies.put(c.getKey(), c.getValue());
                }
                account.setProperty("name", Encoding.urlEncode(account.getUser()));
                account.setProperty("pass", Encoding.urlEncode(account.getPass()));
                account.setProperty("cookies", cookies);
            } catch (final PluginException e) {
                account.setProperty("cookies", Property.NULL);
                throw e;
            }
        }
    }

    @Override
    public String getDescription() {
        return "JDownloader's Deviantart Plugin helps downloading data from deviantart.com.";
    }

    public void setConfigElements() {
        final StringBuilder sbinfo = new StringBuilder();
        String fastlinkchecktext = null;
        String forcehtmldownloadtext = null;
        final String lang = System.getProperty("user.language");
        if ("de".equalsIgnoreCase(lang)) {
            fastlinkchecktext = "Schnelle Linküberprüfung aktivieren? (Dateiname und -größe werden nicht korrekt angezeigt)";
            forcehtmldownloadtext = "HTML Code statt eigentlichen Inhalt (Dateien/Bilder) laden?";
            sbinfo.append("Bitte beachten: solltest Du nur Seite 1 einer Gallerie sammeln wollen, so stelle sicher, dass \"?offset=0\" am Ende der URL steht.\r\n");
            sbinfo.append("Du kannst auch zu einer anderen Seite wechseln, auf Seite 1 klicken und deren URL einfügen.");
        } else {
            fastlinkchecktext = "Enable fast link check? (file name and size won't be shown correctly)";
            forcehtmldownloadtext = "Download html code instead of the media (files/pictures)?";
            sbinfo.append("Please note: if you wanted to grab only page 1 of a gallery, please make sure that \"?offset=0\" is added to its URL.\r\n");
            sbinfo.append("You can also switch to another page, click on page 1 and grab its URL.");
        }
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, getPluginConfig(), FASTLINKCHECK_2, JDL.L("plugins.hoster.deviantartcom.fastLinkcheck", fastlinkchecktext)).setDefaultValue(true));
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, getPluginConfig(), FORCEHTMLDOWNLOAD, JDL.L("plugins.hoster.deviantartcom.forceHTMLDownload", forcehtmldownloadtext)).setDefaultValue(false));
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_SEPARATOR));
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_LABEL, sbinfo.toString()));
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_SEPARATOR));
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }

}