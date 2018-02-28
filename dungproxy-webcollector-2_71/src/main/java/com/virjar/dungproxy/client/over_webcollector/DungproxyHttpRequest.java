package com.virjar.dungproxy.client.over_webcollector;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.zip.GZIPInputStream;

import com.virjar.dungproxy.client.ippool.IpPool;
import com.virjar.dungproxy.client.model.AvProxy;
import com.virjar.dungproxy.client.util.CommonUtil;

import cn.edu.hfut.dmic.webcollector.model.CrawlDatum;
import cn.edu.hfut.dmic.webcollector.net.HttpRequest;
import cn.edu.hfut.dmic.webcollector.net.HttpResponse;

/**
 * Created by virjar on 16/10/30.
 */
public class DungproxyHttpRequest extends HttpRequest {
    // 父类的proxy对象子类没权限访问,所以这里单独维护
    protected Proxy proxy = null;
    protected IpPool ipPool = IpPool.getInstance();

    public DungproxyHttpRequest(CrawlDatum crawlDatum) throws Exception {
        super(crawlDatum);
    }

    public DungproxyHttpRequest(CrawlDatum crawlDatum, Proxy proxy) throws Exception {
        super(crawlDatum);
        this.proxy = proxy;
    }

    public DungproxyHttpRequest(String url) throws Exception {
        super(url);
    }

    public DungproxyHttpRequest(String url, Proxy proxy) throws Exception {
        super(url);
        this.proxy = proxy;
    }

    @Override
    public HttpResponse response() throws Exception {
        URL url = new URL(crawlDatum.url());
        if (userAgent != null) {
            setUserAgent(userAgent);
        }
        HttpResponse response = new HttpResponse(url);
        int code = -1;
        int maxRedirect = Math.max(0, MAX_REDIRECT);
        HttpURLConnection con = null;
        InputStream is = null;
        AvProxy bind = null;
        try {

            for (int redirect = 0; redirect <= maxRedirect; redirect++) {
                if (proxy != null) {
                    con = (HttpURLConnection) url.openConnection(proxy);
                } else {
                    bind = ipPool.bind(CommonUtil.extractDomain(crawlDatum.url()), crawlDatum.url());
                    if (bind != null) {
                        bind.recordUsage();
                        con = (HttpURLConnection) url.openConnection(
                                new Proxy(Proxy.Type.HTTP, new InetSocketAddress(bind.getIp(), bind.getPort())));
                    } else {
                        con = (HttpURLConnection) url.openConnection();
                    }
                }

                config(con);

                if (outputData != null) {
                    OutputStream os = con.getOutputStream();
                    os.write(outputData);
                    os.close();
                }

                code = con.getResponseCode();
                /* 只记录第一次返回的code */
                if (redirect == 0) {
                    response.code(code);
                }

                if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                    response.setNotFound(true);
                    return response;
                }

                boolean needBreak = false;
                switch (code) {

                case HttpURLConnection.HTTP_MOVED_PERM:
                case HttpURLConnection.HTTP_MOVED_TEMP:
                    response.setRedirect(true);
                    if (redirect == MAX_REDIRECT) {
                        throw new Exception("redirect to much time");
                    }
                    String location = con.getHeaderField("Location");
                    if (location == null) {
                        throw new Exception("redirect with no location");
                    }
                    String originUrl = url.toString();
                    url = new URL(url, location);
                    response.setRealUrl(url);
                    LOG.info("redirect from " + originUrl + " to " + url.toString());
                    continue;
                default:
                    needBreak = true;
                    break;
                }
                if (needBreak) {
                    break;
                }

            }

            is = con.getInputStream();
            String contentEncoding = con.getContentEncoding();
            if (contentEncoding != null && contentEncoding.equals("gzip")) {
                is = new GZIPInputStream(is);
            }

            byte[] buf = new byte[2048];
            int read;
            int sum = 0;
            int maxsize = MAX_RECEIVE_SIZE;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            while ((read = is.read(buf)) != -1) {
                if (maxsize > 0) {
                    sum = sum + read;

                    if (maxsize > 0 && sum > maxsize) {
                        read = maxsize - (sum - read);
                        bos.write(buf, 0, read);
                        break;
                    }
                }
                bos.write(buf, 0, read);
            }

            response.content(bos.toByteArray());
            response.headers(con.getHeaderFields());
            bos.close();

            return response;
        } catch (Exception ex) {
            throw ex;
        } finally {
            if (is != null) {
                is.close();
            }
        }
    }
}