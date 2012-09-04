/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2006, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.bpm.report;

import com.google.gson.GsonBuilder;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jboss.bpm.report.model.ReportReference;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

/**
 * BIRT integration facade.<p>
 *
 * @author Heiko.Braun <heiko.braun@jboss.com>
 */
@Path("report")
public class ReportFacade
{
  private static final Log log = LogFactory.getLog(ReportFacade.class);
  private BirtService birtService;
  private boolean isInitialized;
  private boolean initAttempt;

  public ReportFacade() {}
  
  public ReportFacade(ServletContext servletContext)
  {
    try
    {
      if(!initAttempt) initBirtService(servletContext);
    }
    catch (BirtInitException e)
    {
      initAttempt = true; // RIFTSAW-111: gracefully exit when BIRT not installed
      log.info("BIRT service has not been activated. Please check the DEBUG log for further details.");
      log.debug("Initialization failed", e);
    }
  }

  public void initBirtService(ServletContext servletContext)
      throws BirtInitException
  {
    if(!isInitialized)
    {
      IntegrationConfig iConfig = new IntegrationConfig();      
      iConfig.setOutputDir( servletContext.getRealPath("/WEB-INF") + "/output" );
      iConfig.setReportDir( servletContext.getRealPath("/WEB-INF") + "/reports" );
      log.info("Output dir: " +iConfig.getOutputDir());
      log.info("Report dir: " +iConfig.getReportDir());
      try
      {
        this.birtService = new BirtService(iConfig, servletContext);
        this.birtService.createAsync();
      }
      catch (Throwable t)
      {
        throw new BirtInitException(t.getMessage(), t);
      }

      isInitialized = true;
    }
  }

  @GET
  @Path("render/{fileName}")
  @Produces("text/html")
  public Response viewReportHtml(
      @PathParam("fileName")
      String fileName,
      @Context HttpServletRequest request,
      @Context ServletContext servletContext
  )
  {
    assertBirtAvailability();

    try
    {
      RenderMetaData renderMeta = defaultRenderMetaData(fileName, request);

      String outputFileName = birtService.view(renderMeta, servletContext, request);
      File reportFile = new File( birtService.getIntegrationConfig().getOutputDir() + "/" + outputFileName);
      return Response.ok(reportFile).type("text/html").build();
    }
    catch(Throwable e1)
    {
      return gracefulException(e1);
    }
  }

  private void assertBirtAvailability()
  {
    if(!isInitialized)
      throw new IllegalStateException("Report server not initialized. " +
          "Please check the server logs for further details.");
  }

  @POST
  @Path("render/{fileName}")
  @Produces("text/html")
  public Response renderReportHtml(
      @PathParam("fileName")
      String fileName,
      @Context HttpServletRequest request,
      @Context ServletContext servletContext
  )
  {

    assertBirtAvailability();

    try
    {
      RenderMetaData renderMeta = defaultRenderMetaData(fileName, request);
      Map<String,String> postParams = convertRequestParametersToMap(request);
      renderMeta.getParameters().putAll(postParams);

      String outputFileName = birtService.render(renderMeta);
      String absoluteFile = birtService.getIntegrationConfig().getOutputDir() + "/" + outputFileName;
      log.debug("Render " + absoluteFile);

      return Response.ok().type("text/html").build();
    }
    catch(Throwable e1)
    {
      return gracefulException(e1);
    }
  }

  @GET
  @Path("view/image/{fileName}")
  public Response getImage(
      @PathParam("fileName")
      String fileName,
      @Context HttpServletRequest request,
      @Context ServletContext servletContext
  )
  {
    assertBirtAvailability();
    String imageDir = birtService.getIntegrationConfig().getImageDirectory() + "/";
    String absName = imageDir + "/" + fileName;
    File imageFile = new File(absName);
    if(!imageFile.exists()) {
    	throw new IllegalArgumentException("Image " +absName+" doesn't exist");
    }
    return Response.ok(imageFile).build();
  }

  private RenderMetaData defaultRenderMetaData(String fileName, HttpServletRequest request)
  {
    RenderMetaData renderMeta = new RenderMetaData();
    renderMeta.setReportName(fileName);
    renderMeta.setFormat(RenderMetaData.Format.HTML);
    renderMeta.setClassloader(Thread.currentThread().getContextClassLoader());
    renderMeta.setImageBaseUrl(buildImageUrl(request));
    return renderMeta;
  }

  private String buildImageUrl(HttpServletRequest request)
  {
    StringBuffer sb = new StringBuffer();
    sb.append("http://");
    sb.append(request.getServerName()).append(":");
    sb.append(request.getServerPort());
    sb.append(request.getContextPath());
    sb.append(request.getServletPath());
    sb.append("/report/view/image");
    return sb.toString();
  }

  private Response gracefulException(Throwable e)
  {
    log.error("Error processing report", e);

    StringBuffer sb = new StringBuffer();
    sb.append("<div style='font-family:sans-serif; padding:10px;'>");
    sb.append("<h3>Unable to process report").append("</h3>");
    sb.append(e.getMessage());
    sb.append("</div>");
    return Response.ok(sb.toString()).status(400).build();
  }

  static public Map<String, String> convertRequestParametersToMap(HttpServletRequest request){
    HashMap<String, String> parameterMap = new HashMap<String, String>();
    BufferedReader br = null;
    try
    {

      br = request.getReader();
      String line;
      while ((line = br.readLine()) != null)
      {
        StringTokenizer st = new StringTokenizer(line, ";");
        while(st.hasMoreTokens())
        {
          String s = st.nextToken();
          if(s.indexOf("=")!=-1)
          {
            String[] tuple = s.split("=");
            parameterMap.put(tuple[0], tuple[1]);
          }
        }
      }
    }
    catch (IOException e)
    {
      log.error("Failed to parse report parameters", e);
    }
    finally{
      if(br!=null)
        try
        {
          br.close();
        }
        catch (IOException e)
        {
          //
        }
    }

    return parameterMap;
  }

  @GET
  @Path("config")
  @Produces("application/json")
  public Response getReportConfig(@Context ServletContext servletContext)
  {
    assertBirtAvailability();

    List<ReportReference> refs = birtService.getReportReferences();
    String json = new GsonBuilder().create().toJson(refs);
    return Response.ok(json).build();
  }

  public class BirtInitException extends Exception
  {
    public BirtInitException(String message)
    {
      super(message);
    }

    public BirtInitException(String message, Throwable cause)
    {
      super(message, cause);
    }
  }

}
