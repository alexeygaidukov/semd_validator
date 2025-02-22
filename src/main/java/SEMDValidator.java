import org.apache.logging.log4j.Logger;
import org.apache.xerces.util.URI.MalformedURIException;
import org.apache.logging.log4j.LogManager;

import javax.servlet.*;
import javax.servlet.http.*;
import javax.xml.transform.*;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import javax.xml.XMLConstants;
import javax.xml.validation.*;
import javax.xml.parsers.*;
import org.xml.sax.SAXException;
import org.w3c.dom.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.*;

import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.server.Request;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

public class SEMDValidator extends HttpServlet {

    // Constants
    final private static int MILLISEC_TO_DAY = 1000*60*60*24;
    final private static Logger log = LogManager.getLogger(SEMDValidator.class.getName());
    final private static MultipartConfigElement MULTI_PART_CONFIG = new MultipartConfigElement(System.getProperty("java.io.tmpdir"));

    // Settings from web.xml 
    private String DATA_PATH;
    private String ADMIN_NAME;
    private String ADMIN_PASS;
    private String LIST_TYPES_FOR_VARIFICATION[] = {};
    private String FNSI_USERKEY;
    private int ACTUAL_FNSI_CACHE_DAYS = 30; 

    // Значения, загружаемые из файла FNSIlist.txt
    private CopyOnWriteArrayList<String> FNSI_SKIP_LIST = new CopyOnWriteArrayList<String>();
    private ConcurrentHashMap<String, String[]> FNSI_COLS_MAPPING = new ConcurrentHashMap<String, String[]>();

    // Значения, загружаемые из файла FNSITags.txt
    private CopyOnWriteArrayList<String> LIST_TAGS_FOR_VARIFICATION = new CopyOnWriteArrayList<String>();

    // In memory db
    private HashMap<String, Templates> cacheXslt = new HashMap<String, Templates>(100);
    private HashMap<String, Schema> cacheXsd = new HashMap<String, Schema>(100);
    private ConcurrentHashMap<String, HashMap<String, String>> passports = new ConcurrentHashMap<String, HashMap<String, String>>(500);
    private ConcurrentHashMap<String, ConcurrentHashMap<String, String>> refs = new ConcurrentHashMap<String, ConcurrentHashMap<String, String>>(500);

    public void init() throws ServletException {
        System.setProperty("javax.xml.transform.TransformerFactory", "net.sf.saxon.TransformerFactoryImpl");
        System.setProperty("javax.xml.parsers.DocumentBuilderFactory","org.apache.xerces.jaxp.DocumentBuilderFactoryImpl");
        ServletConfig config = this.getServletConfig();

        String param = config.getInitParameter("LIST_TYPES_FOR_VARIFICATION");
        if (param != null && param.length() > 0) {
            String[] types = param.split(",");
            LIST_TYPES_FOR_VARIFICATION = new String[types.length];
            for (int i = 0; i < types.length; i++) {
                LIST_TYPES_FOR_VARIFICATION[i] = types[i].trim();
            }
            Arrays.sort(LIST_TYPES_FOR_VARIFICATION);
        }
        log.info("LIST_TYPES_FOR_VARIFICATION="+Arrays.toString(LIST_TYPES_FOR_VARIFICATION));

        DATA_PATH = config.getInitParameter("DATA_PATH");
        if (DATA_PATH == null)
            throw new ServletException("Servlet param DATA_PATH is not set");

        if (DATA_PATH.charAt(DATA_PATH.length()-1) == '/' || DATA_PATH.charAt(DATA_PATH.length()-1) == '\\')
            DATA_PATH = DATA_PATH.substring(0, DATA_PATH.length()-1);
        log.info("DATA_PATH="+DATA_PATH);

        ADMIN_NAME = config.getInitParameter("ADMIN_NAME");
        if (ADMIN_NAME == null)
            throw new ServletException("Servlet param ADMIN_NAME is not set");
        ADMIN_PASS = config.getInitParameter("ADMIN_PASS");
        if (ADMIN_PASS == null)
            throw new ServletException("Servlet param ADMIN_PASS is not set");

        FNSI_USERKEY = config.getInitParameter("FNSI_USERKEY");
        log.info("FNSI_USERKEY="+FNSI_USERKEY);

        String t = config.getInitParameter("ACTUAL_FNSI_CACHE_DAYS");
        if (t != null) {
            try {
                ACTUAL_FNSI_CACHE_DAYS = Integer.parseInt(t);
            } catch (NumberFormatException ex) {
                log.error("Wrong value of ACTUAL_FNSI_CACHE_DAYS parameter. Should be integer");
            }
        }
        log.info("ACTUAL_FNSI_CACHE_DAYS="+ACTUAL_FNSI_CACHE_DAYS);

        try {
            loadFNSIlist(null);
        } catch (IOException e) {
            throw new ServletException("loadFNSIlist exception - " + e.getMessage());
        }

        try {
            loadFNSITags(null);
        } catch (IOException e) {
            throw new ServletException("loadFNSITags exception - " + e.getMessage());
        }
    }

    // удаляем файлы из fnsi, доступа к которым не было более 7 дней.
    @Override
    public void destroy() {
        log.info("destroy");

        List<String> list = new ArrayList<String>();
        final File folder = new File(DATA_PATH+"/fnsi");
        final File[] fl = folder.listFiles();
        if (fl != null) {
            for (final File fileEntry : fl) {
                if (fileEntry.isFile()) {
                    list.add(fileEntry.getAbsolutePath());
                }
            }
        } else {
            log.error("Folder not found: "+DATA_PATH);
        }

        long t = System.currentTimeMillis();
        for (Iterator<String> i = list.iterator(); i.hasNext();) {
            String item = i.next();
            Path p = Paths.get(item);
            try { 
                BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
                long t1 = attrs.lastAccessTime().toMillis();

                long age = (t-t1)/MILLISEC_TO_DAY;
                if (age > ACTUAL_FNSI_CACHE_DAYS) {
                    p.toFile().delete();
                    log.info("Delete fnsi file: "+item+ ". Age="+age+" days");
                }
            } catch (SecurityException ex) {
                log.error(ex.getMessage(), ex);
            } catch (IOException ex) {
                log.error(ex.getMessage(), ex);
            }
        }
    } 

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) 
            throws ServletException, IOException {

        HttpSession session = req.getSession(false);
        boolean user = false;
        if (session != null && session.getAttribute("user") != null) {
            user = true;
        }
        if (req.getServletPath().equals("/send_sch.html")) {
            log.info(req.getServletPath());
            if (user)
                send_sch(req.getContextPath(), resp);
            else
                loginHtml(req.getContextPath(), resp);
        } else if (req.getServletPath().equals("/get_sch_list.html")) { 
            log.info(req.getServletPath());
            if (user)
                get_sch_list(req.getContextPath(), resp);
            else
                loginHtml(req.getContextPath(), resp);
        } else if (req.getServletPath().equals("/fnsi.html")) { 
            log.info(req.getServletPath());
            if (user)
                fnsi(req.getContextPath(), resp);
            else
                loginHtml(req.getContextPath(), resp);
        } else if (req.getServletPath().equals("/send_semd.html")) {
            log.info(req.getServletPath());
            send_semd(req.getContextPath(), resp);
        } else if (req.getServletPath().equals("/login")) { 
            log.info(req.getServletPath());
            login(req, resp);
        } else if (req.getServletPath().equals("/login.html")) { 
            log.info(req.getServletPath());
            loginHtml(req.getContextPath(), resp);
        } else if (req.getServletPath().startsWith("/file/")) { 
            log.info(req.getServletPath());
            download(req.getContextPath(), req.getServletPath(), resp);
        } else {
            log.warn("PAGE NOT FOUND: "+req.getServletPath());
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) 
            throws ServletException, IOException {

        if (req.getServletPath().equals("/verify")) { 
            log.info(req.getServletPath());
            verify(req, resp);
        } else if (req.getServletPath().equals("/upload")) { 
            log.info(req.getServletPath());
            HttpSession session = req.getSession(false);
            if (session != null && session.getAttribute("user") != null) 
                upload(req, resp);
            else
                resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Authorization Failed");
        } else if (req.getServletPath().equals("/uploadFNSIlist")) { 
            log.info(req.getServletPath());
            HttpSession session = req.getSession(false);
            if (session != null && session.getAttribute("user") != null) 
                uploadFNSIlist(req, resp);
            else
                resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Authorization Failed");
        } else if (req.getServletPath().equals("/uploadFNSITags")) { 
            log.info(req.getServletPath());
            HttpSession session = req.getSession(false);
            if (session != null && session.getAttribute("user") != null) 
                uploadFNSITags(req, resp);
            else
                resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Authorization Failed");
        } else {
            log.warn("PAGE NOT FOUND: "+req.getServletPath());
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
        }
    }

    /**
     * Покахать html страницу авторизации
     * @param contextPath URL сайта для формирования гипер ссылок
     * @param resp Поток для вывода результата
     * @throws IOException
     */
    private void loginHtml(final String contextPath, HttpServletResponse resp) 
            throws IOException {

        resp.setHeader("Content-Type", "text/html; charset=UTF-8");
        PrintWriter out = resp.getWriter();
        out.print(String.join("\n"
        , "<head>"
        , "  <title>Вход в администрирование СЭМД валидатора</title>"
        , "</head>"
        , "<body>"
        , "<H1>Вход в администрирование СЭМД валидатора</H1>"
        , "<form method='get' action='" + contextPath + "/login'>"
        , "  <fieldset>"
        , "  <p><label for='user'>Имя:</label><input type='text' name='user'></p>"
        , "  <p><label for='pass'>Пароль:</label><input type='password' name='pass'></p>"
        , "</fieldset>"
        , "<p>"
        , " <input type='submit' value='Войти'>"
        , " </p>"
        , "</form>"
        , "</body>"
        , "</html>"));
    }

    /**
     * Выполнить авторизацию. Авторизация дает доступ к странице настроек и их изменению.
     * @param req Входящий поток (данные html формы)
     * @param resp Поток для вывода результата
     * @throws IOException
     */
    private void login(HttpServletRequest req, HttpServletResponse resp) 
            throws IOException {
        String user = req.getParameter("user");
        String password = req.getParameter("pass");
        HttpSession session = req.getSession(true);

        if (user != null && password != null && user.equals(ADMIN_NAME) && password.equals(ADMIN_PASS)) {
            session.setAttribute("user", user);
            get_sch_list(req.getContextPath(), resp);
        } else {
            resp.setHeader("Content-Type", "text/html; charset=UTF-8");
            PrintWriter out = resp.getWriter();
            out.print(String.join("\n"
            , "<html>"
            , " <head>"
            , "  <title>Ошибка авторизации</title>"
            , " </head>"
            , " <body>"
            , "  <H1>Ошибка авторизации</H1>"
            , " </head>"
            , " <body>"));
        }
    }

    /**
     * Загрузить СЭМД для валидации. СЭМД загружается через html форму, в которой задаются параметры проверки - тип СЭМД и тип проверки
     * @param req Входящий поток (данные html формы)
     * @param resp Поток для вывода результата
     * @throws IOException
     * @throws ServletException
     */
    private void verify(HttpServletRequest req, HttpServletResponse resp) 
            throws IOException, ServletException {

        if (req.getContentType() != null && req.getContentType().startsWith("multipart/form-data")) {
            req.setAttribute(Request.MULTIPART_CONFIG_ELEMENT, MULTI_PART_CONFIG);
        }
        resp.setHeader("Content-Type", "text/plain; charset=UTF-8");
        PrintWriter out = resp.getWriter();

        final Part filePart = req.getPart("file");
        final String remdtype = req.getParameter("remdtype"); 
        final String verifytype = req.getParameter("verifytype");

         try {
            int vt = Integer.parseInt(verifytype);
            if (vt < 0 || vt > 3) {
                out.print("verifytype parameter should be in (0, 1, 2, 3)");
                return;
            }
         }
         catch (NumberFormatException e) {
            out.print("verifytype parameter should be integer");
            return;
         }

        log.info("REMD type: " + remdtype + ", verify type: " + verifytype);
        InputStream fileContent = filePart.getInputStream();
        String xml = new BufferedReader(new InputStreamReader(fileContent, "UTF-8")).lines().collect(Collectors.joining("\n"));
        // remove BOM
        if (xml.startsWith("\uFEFF")) {
            out.print("XML should be without BOM");
            return;
        }

        if (xml.length() < 50) {
            out.print("XML is broken. Length="+xml.length());
            return;
        }
        if (xml.substring(30, 50).indexOf("UTF-8") == -1) {
            out.print("First line should be '<?xml?>' with encoding='UTF-8'");
            return;
        }

        File xsd = new File(DATA_PATH+"/"+remdtype+"/CDA.xsd");
        boolean valid = true;
        Document document = null;
        boolean filtered = LIST_TYPES_FOR_VARIFICATION.length > 0 && Arrays.binarySearch(LIST_TYPES_FOR_VARIFICATION, remdtype) < 0;
        if (filtered || !xsd.exists() || xsd.isDirectory()) {
            out.print("no xsd - " + remdtype+"/CDA.xsd");
            valid = false;
            if (filtered) {
                log.info("REMD type filtered: " + remdtype);
            }
        } else if (verifytype.equals("0") || verifytype.equals("2") || verifytype.equals("3")) {      // xsd validation
            try {
                document = xsd(xml, xsd.getAbsolutePath());
            } catch (ParserConfigurationException e) {
                out.print(e.getMessage());
                valid = false;
            } catch (SAXException e) {
                out.print(e.getMessage());
                valid = false;
            }
        }

        // fnsi validation
        if (valid && verifytype.equals("3")) {
            for (String tag : LIST_TAGS_FOR_VARIFICATION) {
                NodeList codeList = document.getDocumentElement().getElementsByTagName(tag);
                for (int j = 0; j < codeList.getLength(); j++) {
                    NamedNodeMap attributes = codeList.item(j).getAttributes();
                    validateTag(out, tag, attributes);
                }
            }
        }
        
        // xslt validation
        if (valid && !verifytype.equals("0")) { 
            // remove namespace because schematron validator doesn't see data with any default namespace
            xml = xml.replace("xmlns=\"urn:hl7-org:v3\"", "").replace("xmlns='urn:hl7-org:v3'", "");
            try {
                File folder = new File(DATA_PATH+"/schematrons");
                boolean found = false;
                StringBuffer res = new StringBuffer();
                for (File fileEntry : folder.listFiles()) {
                    if (!fileEntry.isDirectory()) {
                        final String filename = fileEntry.getName();
                        final String ext = filename.substring(filename.length()-4, filename.length());
                        final String name = filename.substring(0, filename.length()-4);
                        if ((name.equals(remdtype) || name.startsWith(remdtype+"_")) && ext.equals(".xsl")) {
                            StringWriter outWriter = new StringWriter();
                            StreamResult result = new StreamResult(outWriter);
                            xslt(new StreamSource(new StringReader(xml)), fileEntry.getAbsolutePath(), result);
                            StringBuffer sb = outWriter.getBuffer();

                            // вырезаем <?xml version="1.0" encoding="UTF-8"?>
                            if (sb.substring(0, 6).equals("<?xml ")) {
                                int i = sb.indexOf(">", 7)+1;
                                res.append("<sch file=\"");
                                res.append(filename);
                                res.append("\">");
                                res.append(sb.substring(i));
                                res.append("</sch>");
                            }
                            found = true;
                        }
                    }
                }
                if (!found) {
                    out.print("error - no files '/schematrons/"+ remdtype+ "*.xsl'");
                }
                else {
                    out.print("<result>");
                    out.print(res.toString());
                    out.print("</result>");
                }
            } catch (TransformerException e) {
                out.print("error sch transfromation: "+ e.getMessageAndLocation().replaceAll("\n", "<br>")+" "+e.getLocator());
                valid = false;
            }
        } else if (valid) {     // для xsd проверки
            out.print("valid");
        }
    }

    /**
     * Загрузка zip архива с xsd и sch для конкретного типа СЭМД.
     * Требования к архиву описаны на странице с формой отправки zip (send_sch)
     * @param req Входящий файл
     * @param resp Поток для вывода результата
     * @throws IOException
     * @throws ServletException
     */
    private void upload(HttpServletRequest req, HttpServletResponse resp) 
            throws IOException, ServletException {

        if (req.getContentType() != null && req.getContentType().startsWith("multipart/form-data")) {
            req.setAttribute(Request.MULTIPART_CONFIG_ELEMENT, MULTI_PART_CONFIG);
        }
        resp.setHeader("Content-Type", "text/plain; charset=UTF-8");
        PrintWriter out = resp.getWriter();

        Part filePart = req.getPart("file");
        final String fileName = Paths.get(filePart.getSubmittedFileName()).getFileName().toString();
        if (fileName.length() < 5) {
            out.print("file name should be like '118.zip'");
            return;
        }
        final String remdtype = fileName.substring(0, fileName.length()-4);
        InputStream fileContent = filePart.getInputStream();
        unzip(DATA_PATH, fileContent);

        try {
            if (!remdtype.startsWith("beta")) {
                if (Integer.parseInt(remdtype) < 1) {
                    out.print("remdtype in filename should be number or like 'beta%'");
                    return;
                }
            } else {
                if (remdtype.contains(" ") || remdtype.contains(".") || remdtype.contains("_")) {
                    throw new IOException("SEMD-beta type should not contains spaces, dots, Underscores");
                }
            }
        }
        catch (NumberFormatException e) {
            out.print("remdtype parameter should be integer");
            return;
        }

        File xsd = new File(DATA_PATH+"/"+remdtype+"/CDA.xsd");
        if (!xsd.exists() ||  xsd.isDirectory()) {
            out.print("no xsd - " + remdtype+"/CDA.xsd");
        } else {
            File folder = new File(DATA_PATH);
            boolean valid = true;
            boolean sch = false;
            for (final File fileEntry : folder.listFiles()) {
                if (!fileEntry.isDirectory()) {
                    final String filename = fileEntry.getName();
                    final String ext = filename.substring(filename.length()-4, filename.length());
                    final String name = filename.substring(0, filename.length()-4);
                    if ((name.equals(remdtype) || name.startsWith(remdtype+"_")) && ext.equals(".sch")) {
                        sch = true;
                        final String xslName = DATA_PATH+"/schematrons/"+name+".xsl";
                        final File xsl = new File(xslName);
                        if (xsl.exists()) {
                            xsl.delete();
                            log.info("Delete file "+xsl);
                        }
                        final String style = getServletContext().getRealPath("/schxslt-1.9.1/2.0/pipeline-for-svrl.xsl");
                        if (style == null) {
                            throw new IOException("Style file not found");
                        }
                        try {
                            xslt(new StreamSource(fileEntry), style, new StreamResult(new FileOutputStream(xsl)));
                        } catch (TransformerException e) {
                            out.print("error sch transfromation: "+ e.getMessage());
                            valid = false;
                        }
                    }
                }
            }
            if (valid && sch) {
                out.print("xsd and sch loaded");
                cacheXslt.clear();
                cacheXsd.clear();
            } else if (valid)
                out.print("xsd loaded");
                cacheXsd.clear();
        }
    }

    /**
     * Загрузка текстового файла с параметрами использования спраовчнико ФНСИ в процесс проверки СЭМД
     * @param req Входящий файл
     * @param resp Поток для вывода результата
     * @throws IOException
     * @throws ServletException
     */
    private void uploadFNSIlist(HttpServletRequest req, HttpServletResponse resp) 
            throws IOException, ServletException {

        if (req.getContentType() != null && req.getContentType().startsWith("multipart/form-data")) {
            req.setAttribute(Request.MULTIPART_CONFIG_ELEMENT, MULTI_PART_CONFIG);
        }
        resp.setHeader("Content-Type", "text/plain; charset=UTF-8");
        PrintWriter out = resp.getWriter();

        Part filePart = req.getPart("file");
        final File file = new File(DATA_PATH + "/FNSIlist.txt");
        InputStream fileContent = filePart.getInputStream();

        byte[] buffer = new byte[2048];
        FileOutputStream fOutput = new FileOutputStream(file);
        int count = 0;
        while ((count = fileContent.read(buffer)) > 0) {
                fOutput.write(buffer, 0, count);
        }
        fOutput.close();
        
        FNSI_SKIP_LIST.clear();
        FNSI_COLS_MAPPING.clear();
        passports.clear();
        refs.clear();
        loadFNSIlist(out);

        out.print("FNSIlist.txt loaded");
    }

    /**
     * Загрузка текстового файла со списков тегов для проверки на соотвествие справочникам ФНСИ
     * @param req Входящий файл
     * @param resp Поток для вывода результата
     * @throws IOException
     * @throws ServletException
     */
    private void uploadFNSITags(HttpServletRequest req, HttpServletResponse resp) 
            throws IOException, ServletException {

        if (req.getContentType() != null && req.getContentType().startsWith("multipart/form-data")) {
            req.setAttribute(Request.MULTIPART_CONFIG_ELEMENT, MULTI_PART_CONFIG);
        }
        resp.setHeader("Content-Type", "text/plain; charset=UTF-8");
        PrintWriter out = resp.getWriter();

        Part filePart = req.getPart("file");
        final File file = new File(DATA_PATH + "/FNSITags.txt");
        InputStream fileContent = filePart.getInputStream();

        byte[] buffer = new byte[2048];
        FileOutputStream fOutput = new FileOutputStream(file);
        int count = 0;
        while ((count = fileContent.read(buffer)) > 0) {
                fOutput.write(buffer, 0, count);
        }
        fOutput.close();
        
        loadFNSITags(out);

        out.print("FNSITags.txt loaded");
    }

    /**
     * HTML страница с формами отправки файлов настроек в сервис валидации, в т.ч. xsd и sch схемы
     * @param contextPath URL сайта для формирования ссылок
     * @param resp Поток для вывода результата
     * @throws IOException
     */
    private void send_sch(final String contextPath, HttpServletResponse resp) 
            throws IOException {

        resp.setHeader("Content-Type", "text/html; charset=UTF-8");
        PrintWriter out = resp.getWriter();
        out.print(String.join("\n"
        , "<html>"
        , " <head>"
        , "  <title>Настройка</title>"
        , " </head>"
        , " <body>"
        , "  <H1>Отправка zip-архива со схемой и схематроном</H1>"
        , "  <form enctype = 'multipart/form-data'  method='post' action='" + contextPath + "/upload'>"
        , "   <input type='file' name='file'/>"
        , "   <input type='submit' value='Отправить zip'/>"
        , "  </form>"
        , "<hr>"
        , "<h3>Структура zip-архива</h3>"
        , "<ul>"
        , "<li>Имя архива - 118.zip, где имя файла - <a href='https://nsi.rosminzdrav.ru/#!/refbook/1.2.643.5.1.13.13.11.1520'>тип РЭМД (OID)</a></li>"
        , "<li>118_1.sch - схематрон (не обязателен). Первое число - <a href='https://nsi.rosminzdrav.ru/#!/refbook/1.2.643.5.1.13.13.11.1520'>тип РЭМД (OID)</a>," 
        , "второе число (необязательное) используется в случае, когда для типа РЭМД существует несколько схематронов</li>"
        , "<li>118 - папка (обязательна). Число - <a href='https://nsi.rosminzdrav.ru/#!/refbook/1.2.643.5.1.13.13.11.1520'>тип РЭМД (OID)</a>.</li>"
        , "<ul>"
        , "<li>CDA.xsd - схема (обязательная). Имя файла должно быть именно таким</li>"
        , "<li>... - другие xsd файл, на которые ссылается головная схема. Друстимы папки, оригинальная иерархия должна быть сохранена</li>"
        , "</ul>"
        , "<li>Второй вариант имени архива - betaOnko.zip. Используется для СЭМД-бета, имя должно начинаться на 'beta' и не содержать символов '.', '_', ' '.<br>"
        , "В этом случае обязательная папка будет иметь название betaOnko. Схемарон (при наличии) также будет иметь имя betaOnko.sch или betaOnko_1.sch</li>"
        , "</ul>"
         , "<hr>"
        , "  <H1>Отправка файла со списком справочников ФНСИ</H1>"
        , "  <form enctype = 'multipart/form-data'  method='post' action='" + contextPath + "/uploadFNSIlist'>"
        , "   <input type='file' name='file'/>"
        , "   <input type='submit' value='Отправить FNSIlist.txt'/>"
        , "  </form>"
         , "<hr>"
        , "  <H1>Отправка файла со списком тегов, подлежащих контролю на соответствие справочникам ФНСИ</H1>"
        , "  <form enctype = 'multipart/form-data'  method='post' action='" + contextPath + "/uploadFNSITags'>"
        , "   <input type='file' name='file'/>"
        , "   <input type='submit' value='Отправить FNSITags.txt'/>"
        , "  </form>"
        , "<h2>См. также</h2>"
        , "<a href='" + contextPath + "/send_semd.html'>Валидация СЭМД</a><br>"
        , "<a href='" + contextPath + "/get_sch_list.html'>Просмотр загруженных схем и схематронов</a><br>"
        , "<a href='" + contextPath + "/fnsi.html'>Кеш справочников ФНСИ</a>"
        , "</body>"
        , "</html>"));
    }

    /**
     * Вывеcти html страницу, содержащую xsd схему.
     * @param contextPath URL сайта для формирования ссылок
     * @param servletPath URL xsd, которая быдет выведена в html
     * @param resp Поток для вывода результата
     * @throws IOException
     */
    private void download(final String contextPath, final String servletPath, HttpServletResponse resp) 
            throws IOException {

        resp.setHeader("Content-Type", "text/html; charset=UTF-8");
        PrintWriter out = resp.getWriter();
        out.print(String.join("\n"
        , "<html>"
        , " <head>"
        , "  <title>", servletPath.substring(5), "</title>"
        , " </head>"
        , " <body>"));

        if (servletPath.contains("..")) {
            out.print(":ERROR: URL '" + servletPath + "' contains '..'");
        } else {
            final String url = DATA_PATH+servletPath.substring(5);   // 5 - это  "/file"
            final int i = servletPath.lastIndexOf("/");     // remove file name
            final String p = contextPath+"/file"+servletPath.substring(5, i+1);   // 5 - это "/file"
            final File file = new File(url);
            if (file.exists()) {
                FileInputStream fis = new FileInputStream(file);
                InputStreamReader isr = new InputStreamReader(fis, StandardCharsets.UTF_8);
                BufferedReader reader = new BufferedReader(isr);
                String line = reader.readLine();
                while (line != null) {
                    String str = escapeHTML(p, line);
                    out.print(str);
                    out.print("<br>");
                    line = reader.readLine();
                }
                reader.close();
            } else {
                out.print("File not found - " + servletPath.substring(5));
            }
        }
        out.print(String.join("\n"
        , "</body>"
        , "</html>"));
    }

    /**
     * HTML страница с выводом всех настроек сервиса валидации
     * @param contextPath URL сайта для формирования ссылок
     * @param resp Поток для вывода результата
     * @throws IOException
     */
    private void get_sch_list(final String contextPath, HttpServletResponse resp) 
            throws IOException {

        resp.setHeader("Content-Type", "text/html; charset=UTF-8");
        PrintWriter out = resp.getWriter();
        out.print(String.join("\n"
        , "<html>"
        , " <head>"
        , "  <title>Список загруженных xsd, sch и сгенерированных xsl из sch</title>"
        , " </head>"
        , " <body>"));

        List<String> list = new ArrayList<String>();
        final File folder = new File(DATA_PATH);
        final File[] fl = folder.listFiles();
        if (fl != null) {
            for (final File fileEntry : fl) {
                if (fileEntry.isFile()) {
                    list.add(fileEntry.getName());
                } else if (fileEntry.isDirectory()) {
                    for (final File fileEntry2 : fileEntry.listFiles()) {
                        if (fileEntry2.isFile() && fileEntry2.getName().compareToIgnoreCase("CDA.xsd") == 0) {
                            list.add(fileEntry.getName()+"/CDA.xsd");
                        }
                    }
                }
            }
        } else {
            throw new IOException("Folder not found: "+DATA_PATH);
        }
        final File folder2 = new File(DATA_PATH+"/schematrons");
        final File[] fl2 = folder2.listFiles();
        if (fl2 != null) {
            for (final File fileEntry : fl2) {
                if (fileEntry.isFile()) {
                    list.add(fileEntry.getName());
                }
            }
        } else {
            throw new IOException("Folder not found: "+DATA_PATH+"/schematrons");
        }
        list.sort(Comparator.naturalOrder());

        out.print("<h2>Список загруженных xsd, sch и сгенерированных xsl из sch</h2>\n");
        if (LIST_TYPES_FOR_VARIFICATION.length > 0) {
            out.print("<h3>Включена выборочная проверка по типам <span style=\"font-weight:bold; background-color: #3366CC;\">"+Arrays.toString(LIST_TYPES_FOR_VARIFICATION)+"</span>. В списке выделены активные типы РЭМД</h3>\n");
        }
        out.print("<table><tr><th>File</th><th>Size in bytes</th><th>Upload date</th></tr>");
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy hh:mm:ss");
        for (Iterator<String> i = list.iterator(); i.hasNext();) {
            String item = i.next();
            String style = "";
            if (LIST_TYPES_FOR_VARIFICATION.length > 0) {
                String n = item.substring(0, item.length()-4);
                if (n.endsWith("CDA") && item.length() > 8) {
                    n = item.substring(0, item.length()-8);     // 102/CDA.xsd
                } else {
                    int idx = n.indexOf('_');
                    if (idx > 0) {
                        n = n.substring(0, idx);        // 102_1.sch
                    }
                }
                if (Arrays.binarySearch(LIST_TYPES_FOR_VARIFICATION, n) >= 0) {
                    style = " style=\"font-weight:bold; background-color: #3366CC;\"";
                }
            }
            String ext = item.substring(item.length()-3, item.length());
            if (ext.equals("sch") || ext.equals("xsd")) {
                File file = new File(DATA_PATH + "/"+item);
                out.print(String.join("", "<tr><td", style, "><a href='./file/", item,"'>", item, "</a></td><td align='right'", style,">", Long.toString(file.length()), "</td><td", style,">", dateFormat.format(file.lastModified()), "</td></tr>\n"));
            } else if (ext.equals("xsl")) {
                File file = new File(DATA_PATH + "/schematrons/"+item);
                out.print(String.join("","<tr><td", style, ">", "schematrons/"+item, "</td><td", style,"></td><td", style,">", dateFormat.format(file.lastModified()), "</td></tr>\n"));
            }
        }

        out.print(String.join("\n"
        , "</table>"));
        if (FNSI_SKIP_LIST.size() > 0) {
            out.println("<H2>Список OID справочников ФНСИ, исключаемых из проверок (FNSIlist.txt)</H2>");
            for (String k : FNSI_SKIP_LIST) {
                out.println(String.join("", k, " - SKIP<br>\n"));
            }
        }
        if (FNSI_COLS_MAPPING.size() > 0) {
            out.println("<H2>Список OID справочников ФНСИ, с отдельными правилами проверки (FNSIlist.txt)</H2>");
            for (String k : FNSI_COLS_MAPPING.keySet()) {
                String[] v = FNSI_COLS_MAPPING.get(k);
                String primary = (v[0] != null) ? "\"PRIMARY\":\""+ v[0]+ "\"" : "";
                String comma = (primary.length() > 0) ? ", " : "";
                String value = (v[1] != null) ? comma+"\"VALUE\":\""+ v[1]+ "\"" : "";
                comma = (value.length() > 0 || comma.length() > 0) ? ", " : "";
                String level = (v[2] != null) ? comma+"\"LEVEL\":\""+ v[2]+ "\"" : "";
                comma = (level.length() > 0 || comma.length() > 0) ? ", " : "";
                String fullname = (v[3] != null) ? comma+"\"FULLNAME\":\""+ v[3]+ "\"" : "";
                comma = (fullname.length() > 0 || comma.length() > 0) ? ", " : "";
                String comment = (v[4] != null) ? comma+"\"COMMENT\":\""+ v[4]+ "\"" : "";
                out.println(String.join("", k, " - {", primary, value, level, fullname, comment, "}<br>"));
            }
        }
        if (LIST_TAGS_FOR_VARIFICATION.size() > 0) {
            out.println("<H2>Список тегов, проверяемых на соответствие ФНСИ (FNSITags.txt)</H2>");
            out.println(String.join("<br>", LIST_TAGS_FOR_VARIFICATION));
        }
        out.print(String.join("\n"
        , "<h2>См. также</h2>"
        , "<a href='" + contextPath + "/send_sch.html'>Настройки</a><br>"
        , "<a href='" + contextPath + "/send_semd.html'>Валидация СЭМД</a><br>"
        , "<a href='" + contextPath + "/fnsi.html'>Кеш справочников ФНСИ</a>"
        , "</body>"
        , "</html>"));
    }


    /**
     * HTML страница с выводом содержимого папки fnsi, где сохраняются справочники ФНСИ
     * @param contextPath URL сайта для формирования ссылок
     * @param resp Поток для вывода результата
     * @throws IOException
     */
    private void fnsi(final String contextPath, HttpServletResponse resp) 
            throws IOException {

        resp.setHeader("Content-Type", "text/html; charset=UTF-8");
        PrintWriter out = resp.getWriter();
        out.print(String.join("\n"
        , "<html>"
        , " <head>"
        , "  <title>Кеш справочников ФНСИ</title>"
        , " </head>"
        , " <body>"));

        List<String> list = new ArrayList<String>();
        final File folder = new File(DATA_PATH+"/fnsi");
        final File[] fl = folder.listFiles();
        if (fl != null) {
            for (final File fileEntry : fl) {
                if (fileEntry.isFile()) {
                    list.add(fileEntry.getName());
                }
            }
        } else {
            throw new IOException("Folder not found: "+DATA_PATH);
        }
        list.sort(Comparator.naturalOrder());

        out.print("<h2>Кеш справочников ФНСИ</h2>\n");
        out.print("<table><tr><th>File</th><th>Size in bytes</th><th>Upload date</th></tr>");
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy hh:mm:ss");
        for (Iterator<String> i = list.iterator(); i.hasNext();) {
            String item = i.next();
            String style = "";
            File file = new File(DATA_PATH + "/fnsi/"+item);
            out.print(String.join("", "<tr><td", style, "><a href='./file/fnsi/", item,"'>", item, "</a></td><td align='right'", style,">", Long.toString(file.length()), "</td><td", style,">", dateFormat.format(file.lastModified()), "</td></tr>\n"));
        }

        out.print(String.join("\n"
        , "</table>"));
        out.print(String.join("\n"
        , "<h2>См. также</h2>"
        , "<a href='" + contextPath + "/send_sch.html'>Настройки</a><br>"
        , "<a href='" + contextPath + "/send_semd.html'>Валидация СЭМД</a><br>"
        , "<a href='" + contextPath + "/get_sch_list.html'>Просмотр загруженных схем и схематронов</a>"
        , "</body>"
        , "</html>"));
    }

    /**
     * HTML страница с формой для отправки СЭМД на валидацию
     * @param contextPath URL сайта для формирования ссылок
     * @param resp Поток для вывода результата
     * @throws IOException
     */
    private void send_semd(final String contextPath, HttpServletResponse resp) 
            throws IOException {

        resp.setHeader("Content-Type", "text/html; charset=UTF-8");
        PrintWriter out = resp.getWriter();
        out.print(String.join("\n"
        , "<head>"
        , "  <title>Валидация СЭМД</title>"
        , "</head>"
        , "<body>"
        , "<H1>Валидация СЭМД</H1>"
        , "<form enctype = 'multipart/form-data' method='post' action='" + contextPath + "/verify'>"
        , "  <fieldset>"
        , "   <p><label for='remdtype'><a href='https://nsi.rosminzdrav.ru/#!/refbook/1.2.643.5.1.13.13.11.1520'>Тип РЭМД (OID)</a>:</label><input type='text' name='remdtype'></p>"
        , "   <p><label for='verifytype'>Тип валидации. 0 - xsd, 1 - sch, 2 - xsd+sch, 3 - xsd+sch+fnsi :</label><input type='text' name='verifytype' value='3'></p>"
        , "  </fieldset>"
        , "  <p>"
        , "   <input type='file' name='file'>"
        , "   <input type='submit' value='Отправить СЭМД'>"
        , "  </p>"
        , "</form>"
        , "<hr>"
        , "<h2>См. также</h2>"
        , "<a href='" + contextPath + "/send_sch.html'>Настройки</a><br>"
        , "<a href='" + contextPath + "/get_sch_list.html'>Просмотр загруженных схем и схематронов</a><br>"
        , "<a href='" + contextPath + "/fnsi.html'>Кеш справочников ФНСИ</a>"
        , "</body>"
        , "</html>"));
    }

    /**
     * Разархивация zip файла
     * @param destinationFolder Каталог, куда будут сохранены файлы архива
     * @param zipFile Исходный файл
     * @throws IOException
     */
    private static void unzip(final String destinationFolder, InputStream zipFile) 
            throws IOException {

        ZipInputStream zipInput = new ZipInputStream(zipFile);
        ZipEntry entry = zipInput.getNextEntry();
        byte[] buffer = new byte[2048];
           
        while (entry != null) {
            final String entryName = entry.getName();
            final File file = new File(destinationFolder + File.separator + entryName);
                
            if (entry.isDirectory()) {
                Files.createDirectories(file.toPath());
            } else {
                FileOutputStream fOutput = new FileOutputStream(file);
                int count = 0;
                while ((count = zipInput.read(buffer)) > 0) {
                     fOutput.write(buffer, 0, count);
                }
                fOutput.close();
            }
            zipInput.closeEntry();
            entry = zipInput.getNextEntry();
        }
        zipInput.closeEntry();
        zipInput.close();
    }    

    /**
    * getServletInfo<BR>
    * Required by Servlet interface
    */
    public String getServletInfo() {
        return "SEMD Validator";
    }


    /**
     * Выполнить xslt трасформацию для xml
     * @param source Поток с xml
     * @param style Стилевой файл для выполнения трансформации
     * @param dst Поток с результатом трансформации
     * @throws TransformerException
     */
    private void xslt(StreamSource source, final String style, StreamResult dst)
            throws TransformerException {

        final Templates pss = tryXsltCache(style);
        Transformer transformer = pss.newTransformer();
        transformer.transform(source, dst);
    }

    /**
     * Построить DOM для xml и выполнить проверку на соответствие схеме. Схема по возможности берется из кеша
     * @param xml Строка с xml
     * @param xsd Путь к файлу со схемой
     * @return DOM для xml
     * @throws IOException
     * @throws ParserConfigurationException
     * @throws SAXException
     */
    private Document xsd(final String xml, final String xsd)
            throws IOException, ParserConfigurationException, SAXException {

        final Schema s = tryXsdCache(xsd);
        DocumentBuilderFactory builderFactory = DocumentBuilderFactory.newInstance("org.apache.xerces.jaxp.DocumentBuilderFactoryImpl", null);
        builderFactory.setNamespaceAware(true);
        builderFactory.setSchema(s);
        DocumentBuilder parser = builderFactory.newDocumentBuilder();
        InputStream stream = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
        XsdErrorHandler handler = new XsdErrorHandler();
        parser.setErrorHandler(handler);
        Document document = parser.parse(stream);
        String error = handler.getMessage();
        if (error.length() > 0) {
            throw new SAXException(error);
        }
        return document;
    }


    /**
     * Получить шаблон xslt трасформации по xsl. Если шаблон сохранен в кеше, то взять из кеша. Иначе создается новый и сохраняется в кеше
     * @param path Путь к файлу с стилем странсформации
     * @return шаблон xslt трасформации
     * @throws TransformerException
     */
    private synchronized Templates tryXsltCache(final String path) 
            throws TransformerException {

        Templates x = cacheXslt.get(path);
        if (x == null) {
            TransformerFactory factory = TransformerFactory.newInstance();
            x = factory.newTemplates(new StreamSource(new File(path)));
            cacheXslt.put(path, x);
        }
        return x;
    }
    /**
     * Получить схему. Если схема сохранена в кеше, то взять из кеша. Иначе создается новая и сохраняется в кеше
     * @param path Путь к файлу со схемой
     * @return Схему для использования в процессе валидации
     * @throws SAXException
     */
    private synchronized Schema tryXsdCache(final String path) 
            throws SAXException {

        Schema s = cacheXsd.get(path);
        if (s == null) {
            SchemaFactory sf = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            s = sf.newSchema(new StreamSource(new File(path)));
            cacheXsd.put(path, s);
        }
        return s;
    }

    /**
     * Выполнить трасформацию xsd схемы в html.
     * Директива <include> схемы преобразуется в гипер ссылку.
     * @param path Путь к сайту, используемый для формирования гипер ссылок
     * @param s исходный xml
     * @return результирующий html
     */
    public static String escapeHTML(final String path, final String s) {
        StringBuilder out = new StringBuilder(Math.max(16, s.length()));
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c > 127 || c == '"' || c == '\'' || c == '<' || c == '>' || c == '&') {
                out.append("&#");
                out.append((int) c);
                out.append(';');
            } else if (c == ' ') {
                out.append("&nbsp;");
            } else {
                out.append(c);
            }
        }

        int i1 = out.indexOf("include");
        if (i1 > 0) {
            int i2 = out.indexOf("schemaLocation", i1+8);
            if (i2 >= 0) {
                int i3 = out.indexOf("&#39;", i2+15);  // символ '
                if (i3 < 0) {
                    i3 = out.indexOf("&#34;", i2+15);  // символ "
                }
                int i4 = out.indexOf("&#39;", i3+5);  // символ '
                if (i4 < 0) {
                    i4 = out.indexOf("&#34;", i3+5);  // символ "
                }
                if (i3 > 0 && i4 > 0) {
                    String file = out.substring(i3+5, i4);
                    String out2 = out.substring(0, i3+5)+ "<a href='"+path+file+"'>"+file+"</a>"+out.substring(i4);
                    return out2;
                }
            }
        }
        return out.toString();
    }
    
    /**
     * Загрузка файла с настройками проверки справочников ФНСИ
     * @param resp - поток для вывода результата проверки
     * @throws IOException
     */
    private synchronized void loadFNSIlist(PrintWriter resp) throws IOException {
        final File file = new File(DATA_PATH+"/FNSIlist.txt");
        if (file.exists()) {
            FileInputStream fis = new FileInputStream(file);
            InputStreamReader isr = new InputStreamReader(fis, StandardCharsets.UTF_8);
            BufferedReader reader = new BufferedReader(isr);
            String line = reader.readLine();
            int ln = 1;
            JSONParser parser = new JSONParser();
            try {
                while (line != null) {
                    int i = line.indexOf("-", 0);
                    if (i < 0) {
                        String msg = "В строке "+ln+" нет символа '-'";
                        if (resp != null)
                            resp.print(msg);
                        else
                            log.warn(msg);
                    } else {
                        if (line.substring(i).indexOf("SKIP") >= 0 && line.substring(i).indexOf("{") < 0) {
                            FNSI_SKIP_LIST.add(line.substring(0, i).trim());
                        } else {
                            JSONObject obj = (JSONObject)parser.parse(line.substring(i+1));
                            String[] arr = new String[5];
                            arr[0] = (String)obj.get("PRIMARY");
                            arr[1] = (String)obj.get("VALUE");
                            String level = (String)obj.get("LEVEL");
                            if (level == null || !(level.equals("ERROR") || level.equals("SKIP_displayName")))
                                level = "ERROR";
                            arr[2] = level;
                                FNSI_COLS_MAPPING.put(line.substring(0, i).trim(), arr);
                            arr[3] = (String)obj.get("FULLNAME");
                            arr[4] = (String)obj.get("COMMENT");
                        }
                    }
                    line = reader.readLine();
                    ln++;
                }
            } catch (ParseException ex) {
                if (resp != null) {
                    resp.println("Line-"+ln);
                    resp.print(ex);
                } else {
                    log.warn("Line-"+ln);
                    log.warn(ex.getMessage(), ex);
                }
                throw new RuntimeIOException(ex);
            } catch (RuntimeException ex) {
                if (resp != null) {
                    resp.println("Line-"+ln);
                } else {
                    log.warn("Line-"+ln);
                    log.warn(ex.getMessage(), ex);
                }
            }
            finally {
                reader.close();
            }
            FNSI_SKIP_LIST.sort(null);
        }
    }

    /**
     * Загрузка файла со списком тегов СЭМД, подлежащих проверке на соответствие справочникам ФНСИ
     * @param resp - поток для вывода результата проверки
     * @throws IOException
     */
    private synchronized void loadFNSITags(PrintWriter resp) throws IOException {
        final File file = new File(DATA_PATH+"/FNSITags.txt");
        LIST_TAGS_FOR_VARIFICATION.clear();
        if (file.exists()) {
            FileInputStream fis = new FileInputStream(file);
            InputStreamReader isr = new InputStreamReader(fis, StandardCharsets.UTF_8);
            BufferedReader reader = new BufferedReader(isr);
            String line = reader.readLine();
            while (line != null) {
                LIST_TAGS_FOR_VARIFICATION.add(line.trim());
                line = reader.readLine();
            }
            reader.close();
        }
    }

    /**
     * Вроверка тега на соответствие ФНСИ
     * @param resp - поток для вывода результата проверки
     * @param tag - какой тег сейчас проверяется
     * @param attributes - атрибуты проверяемого тега
     * @return возвращает true в случае успешной прохождения проверки, false при наличии ошибки
     */
    private boolean validateTag(PrintWriter resp, final String tag, final NamedNodeMap attributes) {
        final Node codeSystem = attributes.getNamedItem("codeSystem");
        if (codeSystem == null) {
            return true;
        }
        final Node codeSystemVersion = attributes.getNamedItem("codeSystemVersion");
        if (codeSystemVersion == null) {
            return true;
        }
        String[] key = new String[2];
        key[0] = codeSystem.getNodeValue();
        key[1] = codeSystemVersion.getNodeValue();
        final HashMap<String, String> passport = getPassport(resp, tag, key);
        if (passport != null) {
            return getRef(resp, tag, attributes, passport);
        } else {
            return true;
        }        
    }

    /**
     * Получаем паспорт справочника и записываем паспорт в файл
     * Если паспорт был получен ранее, то читаем его с диска
     * Фукнция возвращает хеш с ключевыми данными паспорта - rowsCount, codeSystem, codeSystemName, codeSystemVersion, PRIMARY, VALUE
     * @param resp - поток для вывода результата проверки
     * @param tag - какой тег сейчас проверяется
     * @param codeSystem - массив из 2-х строк, 1ая - OID справочник, 2-ая - версия справочника
     * @return Возвращает данные паспорта - название тега со значением ключа и название поля со значением
     */
    private HashMap<String, String> getPassport(PrintWriter resp, final String tag, final String[] codeSystem) {
        final String key = String.join("_", codeSystem);
        HashMap<String, String> ret = passports.get(key);
        if (ret != null)
            return ret;

        final File file = new File(DATA_PATH + "/fnsi/"+key+"_passport");
        if (!file.exists()) {
            try {
                URL fnsi = new URL("https://nsi.rosminzdrav.ru/port/rest/passport?userKey="+FNSI_USERKEY+"&identifier="+codeSystem[0]+"&version="+codeSystem[1]);
                log.info("Download passport "+fnsi);
                URLConnection conn = fnsi.openConnection();
                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                String inputLine;
                FileOutputStream fOutput = new FileOutputStream(file);
                while ((inputLine = in.readLine()) != null) 
                    fOutput.write(inputLine.getBytes());
                in.close();
                fOutput.close();
            } catch (MalformedURIException ex) {
                log.error(ex.getMessage(), ex);
                return null;
            } catch (IOException ex) {
                log.error(ex.getMessage(), ex);
                resp.print(":ERROR: Tag:"+tag+". Passport "+codeSystem[0]+" version "+codeSystem[1]+" was not found. IOException - "+ex+"\n");
                if (file.exists()) {
                    log.info("Delete wrong file (IOException) "+file);
                    file.delete();
                }
                return null;
            }
        }
        try {
            log.info("Reading file "+file);
            JSONParser parser = new JSONParser();
            JSONObject obj = (JSONObject)parser.parse(new FileReader(file.getAbsolutePath()));
            Object rowsCount = obj.get("rowsCount");
            String fullName = (String)obj.get("fullName");
            JSONArray keys = (JSONArray)obj.get("keys");
            if (obj.get("result").equals("OK") && rowsCount != null && fullName != null && keys != null) {
                String[] arr = FNSI_COLS_MAPPING.get(codeSystem[0]);
                String primary_id = (arr == null) ? null : arr[0];
                String value_name = (arr == null) ? null : arr[1];
                if (arr != null && arr[3] != null) {
                    fullName = arr[3];
                }
                ret = new HashMap<String, String>();
                ret.put("rowsCount", rowsCount.toString());
                ret.put("codeSystem", codeSystem[0]);
                ret.put("codeSystemVersion", codeSystem[1]);
                ret.put("codeSystemName", fullName);
                Iterator<JSONObject> i = keys.iterator();
                while (i.hasNext() && !(primary_id != null && value_name != null)) {
                    JSONObject ikey = i.next();
                    String type = (String)ikey.get("type");
                    if (primary_id == null && type.equals("PRIMARY")) {
                        primary_id = (String)ikey.get("field");
                    } else if (value_name == null && type.equals("VALUE")) {
                        value_name = (String)ikey.get("field");
                    }
                }
                ret.put("PRIMARY", primary_id);
                ret.put("VALUE", value_name);
                passports.put(key, ret);
                FileTime fileTime = FileTime.fromMillis(System.currentTimeMillis());
                touch(file, fileTime);
                return ret;
            } else {
                resp.println(":ERROR: Tag-"+tag+". Справочника c OID ["+codeSystem[0]+"], версия ["+codeSystem[1]+"] не существует или имеет неверное содержимое");
                log.info("Delete wrong passport - RESULT != OK: "+file);
                file.delete();
            }
        }
        catch (IOException ex) {
            resp.print("WARNING: "+ex);
            log.error("Passport-"+key+". "+ex);
            ex.printStackTrace(resp);
        }
        catch (ParseException ex) {
            resp.print("WARNING: "+ex);
            log.error("Passport-"+key+". "+ex);
            ex.printStackTrace(resp);
            if (file.exists()) {
                log.info("Delete wrong file (ParseException) "+file);
                file.delete();
            }
        }
        return null;
    }

    /**
    * Проверка тега code на соответствие ФНСИ. 
    * Для проверки получаем справочник и записываем в файл
    * Если справочник был получен ранее, то читаем его с диска в хеш. Тоже самое делаем сначала с паспортом справочника.
    * Значение атрибута code сопоставляется со значение поля, указанного как PRIMARY в массиве keys в паспорте справочника.
    * Значение атрибута displayName сопоставляется со значение поля, указанного как VALUE в массиве keys в паспорте справочника.
    * Значение атрибута codeSystemName сопоставляется со значение поля fullName в паспорте справочника.
     * @param resp - поток для вывода результата проверки
     * @param tag - какой тег сейчас проверяется
     * @param attributes - атрибуты проверяемого тега
     * @param passport - данные паспорта - название тега со значением ключа и название поля со значением
     * @return возвращает true в случае успешной прохождения проверки, false при наличии ошибки
     */
    private boolean getRef(PrintWriter resp, final String tag, final NamedNodeMap attributes, final HashMap<String, String> passport) {
        String code = null;
        String codeSystemName = null;
        String displayName = null;
        Node node = attributes.getNamedItem("code");
        if (node == null) {
            return true;
        }
        code = node.getNodeValue();
        node = attributes.getNamedItem("displayName");
        if (node == null) {
            return true;
        }
        displayName = node.getNodeValue();
        node = attributes.getNamedItem("codeSystemName");
        if (node == null) {
            return true;
        }
        codeSystemName = node.getNodeValue();

        final String codeSystem = passport.get("codeSystem");
        final String codeSystemVersion = passport.get("codeSystemVersion");
        final String key = codeSystem+"_"+codeSystemVersion;
        if (Collections.binarySearch(FNSI_SKIP_LIST, codeSystem) >= 0) {
            return true;
        }

        final String[] arr = FNSI_COLS_MAPPING.get(codeSystem);
        final String level = (arr != null) ? arr[2] : "ERROR";
        if (!passport.get("codeSystemName").equals(codeSystemName) && !level.equals("SKIP_codeSystemName")) {
            resp.println(":ERROR: Tag-"+tag+". Неверный codeSystemName. Справочник OID ["+codeSystem+"], версия ["+codeSystemVersion+"]. '"+passport.get("codeSystemName")+"' != '"+codeSystemName+"'");
            return false;
        }

        final String primary_id = passport.get("PRIMARY");
        final String value_name = passport.get("VALUE");
        if (primary_id == null || value_name == null) {
            resp.println(":WARNING: Tag-"+tag+". Для справочника "+codeSystem+", версия ["+codeSystemVersion+"] '"+codeSystemName+"' не установлены PRIMARY и VALUE");
            return true;
        }

        final int rowsCount = Integer.parseInt(passport.get("rowsCount"));
        ConcurrentHashMap<String, String> ref = refs.get(key);
        if (ref == null) {
            final int c = (rowsCount-1)/2000+1;
            FileTime fileTime = FileTime.fromMillis(System.currentTimeMillis());
            for (int i=1; i <= c; i++) {
                final File file = new File(DATA_PATH + "/fnsi/"+key+"_part"+i);
                if (!file.exists()) {
                    try {
                        FileOutputStream fOutput = new FileOutputStream(file);
                        URL fnsi = new URL("https://nsi.rosminzdrav.ru/port/rest/data?userKey="+FNSI_USERKEY+"&identifier="+codeSystem+"&version="+codeSystemVersion+"&page="+i+"&size=2000");
                        log.info("Download data "+fnsi);
                        URLConnection conn = fnsi.openConnection();
                        BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                        String inputLine;
                        while ((inputLine = in.readLine()) != null) 
                            fOutput.write(inputLine.getBytes());
                        in.close();
                        fOutput.close();
                    } catch (MalformedURIException ex) {
                        log.error(ex.getMessage(), ex);
                        return true;
                    } catch (IOException ex) {
                        log.error(ex.getMessage(), ex);
                        if (file.exists()) {
                            log.info("Delete wrong file (IOException) "+file);
                            file.delete();
                        }
                        return true;
                    }
                }
            }

            ref = new ConcurrentHashMap<String, String>();
            for (int i=1; i <= c; i++) {
                final File file = new File(DATA_PATH + "/fnsi/"+key+"_part"+i);
                if (file.exists()) {
                    try {
                        log.info("Reading file "+file+ " PRIMARY-'"+primary_id+ "'' VALUE-'"+value_name+"'");
                        JSONParser parser = new JSONParser();
                        JSONObject obj = (JSONObject)parser.parse(new FileReader(file.getAbsolutePath()));
                        if (obj.get("result").equals("OK")) {
                            JSONArray list = (JSONArray)obj.get("list");
                            Iterator it = list.iterator();
                            while (it.hasNext()) {
                                JSONArray row = (JSONArray)it.next();
                                Iterator<JSONObject> i2 = row.iterator();
                                String data_primary = null;
                                String data_value = null;
                                boolean data_primary_loaded = false;
                                boolean data_value_loaded = false;
                                while (i2.hasNext()) {
                                    JSONObject field = i2.next();
                                    String col = (String)field.get("column");
                                    if (col.equals(primary_id)) {
                                        data_primary = (String)field.get("value");
                                        data_primary_loaded = true;
                                        if (data_value_loaded)
                                            break;
                                    } else if (col.equals(value_name)) {
                                        data_value = (String)field.get("value");
                                        data_value_loaded = true;
                                        if (data_primary_loaded)
                                            break;
                                    }
                                }
                                if (data_primary_loaded && data_value_loaded) {
                                    if (data_primary != null && data_value != null)     // в справочнике МКБ есть нулевые записи
                                        ref.put(data_primary, data_value);
                                } else {
                                    String msg = ":WARNING: Tag-"+tag+". Для справочника "+codeSystem+", версия ["+codeSystemVersion+"] '"+codeSystemName+"'  PRIMARY или VALUE установлен ошибочно - "+data_primary +" "+ data_value;
                                    resp.println(msg);
                                    log.error(msg);
                                    return true;
                                }
                            }
                        }
                    }
                    catch (ParseException ex) {
                        log.error("Passport-"+key+". "+ex);
                        ex.printStackTrace(resp);
                        if (file.exists()) {
                            log.info("Delete wrong file (ParseException) "+file);
                            file.delete();
                        }
                        return false;
                    } catch (IOException ex) {
                        log.error(ex.getMessage(), ex);
                        return false;
                    }
                } else {
                    String msg = "Не найден справочник "+file+"! Ошибка программы";
                    resp.println(msg);
                    log.error(msg);
                    return true;
                }
                touch(file, fileTime);
                refs.put(key, ref);
            }
        }
        String dn = ref.get(code);
        if (dn == null) {
            resp.println(":ERROR: Tag-"+tag+". INVALID_ELEMENT_VALUE_CODE Справочник OID ["+codeSystem+"], версия ["+codeSystemVersion+"]. Элемент с кодом ["+code+"] отсутствует");
            return false;
        }
        if (!dn.equals(displayName)) {
            if (!level.equals("SKIP_displayName")) {
                resp.println(":"+level+": Tag-"+tag+". INVALID_ELEMENT_VALUE_NAME Справочник OID ["+codeSystem+"], версия ["+codeSystemVersion+"]. Наименование элемента ["+displayName+"] с кодом ["+code+"] не соответствует наименованию элемента в НСИ ["+dn+"]");
                return false;
            }
        }
        return true;
    }

    /**
     * Действие аналогичное команде 'touch -a' в линуксе.
     * @param file - файл
     * @param fileTime - какое время установить
     */
    private static void touch(final File file, final FileTime fileTime) {
        try {
            Path p = Paths.get(file.getAbsolutePath());
            Files.setAttribute(p, "lastAccessTime", fileTime);            
        } catch (IllegalArgumentException ex) {
            log.error(ex.getMessage(), ex);
        } catch (SecurityException ex) {
            log.error(ex.getMessage(), ex);
        } catch (IOException ex) {
            log.error(ex.getMessage(), ex);
        } 
    }
}
