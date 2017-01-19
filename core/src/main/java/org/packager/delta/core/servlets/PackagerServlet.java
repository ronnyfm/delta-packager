package org.packager.delta.core.servlets;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.servlet.ServletException;

import org.apache.felix.scr.annotations.sling.SlingServlet;
import org.apache.jackrabbit.vault.fs.api.PathFilterSet;
import org.apache.jackrabbit.vault.fs.api.ProgressTrackerListener;
import org.apache.jackrabbit.vault.fs.config.DefaultWorkspaceFilter;
import org.apache.jackrabbit.vault.fs.filter.DefaultPathFilter;
import org.apache.jackrabbit.vault.packaging.JcrPackage;
import org.apache.jackrabbit.vault.packaging.JcrPackageDefinition;
import org.apache.jackrabbit.vault.packaging.JcrPackageManager;
import org.apache.jackrabbit.vault.packaging.PackageException;
import org.apache.jackrabbit.vault.packaging.PackageId;
import org.apache.jackrabbit.vault.packaging.PackagingService;
import org.apache.jackrabbit.vault.util.DefaultProgressListener;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.search.PredicateGroup;
import com.day.cq.search.Query;
import com.day.cq.search.QueryBuilder;

/**
 * Creates a package with default filter configuration and the specified date
 * 
 * @author ronny.fallas
 *
 */
@SlingServlet(
        name = "Packager Utility",        
        resourceTypes = { "sling/servlet/default" },
        selectors = { "package" },
        extensions = { "json" },
        methods = "GET",
        generateService = true,
        generateComponent = true,
        metatype = false)
public class PackagerServlet extends SlingSafeMethodsServlet 
{	
	private static final long serialVersionUID = 1L;	
	private static final Logger LOG = LoggerFactory.getLogger(PackagerServlet.class);
	
	private static final String FROM_PARAM = "from";
	private static final String CONTENT_PARAM = "content";
	private static final String DAM_PARAM = "dam";
	private static final String DAM_PATH = "/content/dam/%s";
	private static final String DAM_EXCLUDE_RENDITIONS = "%s/(?:[^/]+/)*cq5dam\\.(?:[^.]+\\.)*(?:png|jpeg|gif)";
	
	/**
	 * Builds a package according to the specified date and optionally content and dam parameters.
	 */
	@Override
    protected void doGet(final SlingHttpServletRequest request, final SlingHttpServletResponse response) throws ServletException, IOException
    {
	    // Get resource resolver and read parameters	    
	    final ResourceResolver resolver = request.getResourceResolver();
	    
	    final String lastModified = request.getParameter(FROM_PARAM);
	    final String contentPath = request.getParameter(CONTENT_PARAM);
	    final String damPath = String.format(DAM_PATH, request.getParameter(DAM_PARAM));
	    final String excludeRenditions = String.format(DAM_EXCLUDE_RENDITIONS, damPath);
	    
	    // First, get resources modified after date
	    // Start with content
	    Map<String, String> contentPredicates = new HashMap<>();

        contentPredicates.put("path", contentPath);
        contentPredicates.put("type", "cq:Page");
        contentPredicates.put("daterange.property", "jcr:content/cq:lastModified");
        contentPredicates.put("daterange.lowerBound", lastModified);
        contentPredicates.put("daterange.lowerOperation", ">=");	    

	    QueryBuilder contentQueryBuilder = resolver.adaptTo(QueryBuilder.class);
	    Session session = resolver.adaptTo(Session.class);

	    Query query = contentQueryBuilder.createQuery(PredicateGroup.create(contentPredicates), session);
	    query.setHitsPerPage(0); // return ALL results
	    Iterator<Resource> pageIterator = query.getResult().getResources();
	    
	    // Then DAM
	    Map<String, String> damPredicates = new HashMap<>();

	    damPredicates.put("path", damPath);
	    damPredicates.put("type", "dam:Asset");
	    damPredicates.put("daterange.property", "jcr:content/jcr:lastModified");
	    damPredicates.put("daterange.lowerBound", lastModified);
	    damPredicates.put("daterange.lowerOperation", ">=");        

        QueryBuilder damQueryBuilder = resolver.adaptTo(QueryBuilder.class);        

        Query damQuery = damQueryBuilder.createQuery(PredicateGroup.create(damPredicates), session);
        damQuery.setHitsPerPage(0);
        Iterator<Resource> damIterator = damQuery.getResult().getResources();
	    	    
	    // Second, add found resources as filters rules for the package
	    DefaultWorkspaceFilter filters = new DefaultWorkspaceFilter();
	    
	    while(pageIterator.hasNext())
	    {
	        Resource currentResource = pageIterator.next();	       	        
	        filters.add(new PathFilterSet(currentResource.getPath()));
	    }
	    
	    while(damIterator.hasNext())
        {
            Resource currentResource = damIterator.next(); 
            PathFilterSet filter = new PathFilterSet(currentResource.getPath());
            filter.addExclude(new DefaultPathFilter(excludeRenditions));
            
            filters.add(filter);            
        }
	    
	    // Third, package them        
	    try
	    {
	        JcrPackageManager packageManager = PackagingService.getPackageManager(session);
	        
	        // Delete previous package
	        deletePackage(packageManager, "my-packages", "delta-package", "1");
	        
	        // Creates new definition
	        JcrPackage deltaPackage = packageManager.create("my-packages","delta-package", "1");
	        
	        JcrPackageDefinition jcrPackageDefinition = deltaPackage.getDefinition();
	        jcrPackageDefinition.setFilter(filters, true);

            // Assemble the package
            this.assemblePackage(deltaPackage, packageManager);                        
	    }
	    catch(RepositoryException | PackageException re)
	    {
	        LOG.error("Failed to package the returned results", re);	        
	    }
    }
	
	/**
	 * Assemble the package on the server (i.e. build the zip).
     *
     * @param jcrPackage the package to build 
	 * @param packageManager the package manager
	 * @throws PackageException
	 * @throws RepositoryException
	 * @throws IOException
	 */
    private void assemblePackage(final JcrPackage jcrPackage, final JcrPackageManager packageManager) throws PackageException, RepositoryException, IOException
    {
        // This method will build the package.
        ProgressTrackerListener listener = new DefaultProgressListener();
        packageManager.assemble(jcrPackage, listener);
        LOG.debug("Package assembled");
    }
    
    /**
     * Deletes a package 
     * 
     * @param packageManager
     * @param group
     * @param name
     * @param version
     * @throws RepositoryException 
     */
    private void deletePackage(final JcrPackageManager packageManager, final String group, final String name, final String version) throws RepositoryException
    {
        PackageId packageId = new PackageId(group, name, version);
        JcrPackage jcrPackage = packageManager.open(packageId);
        
        if (jcrPackage != null && jcrPackage.getNode() != null)
        {
            jcrPackage.getNode().remove();
            jcrPackage.getNode().getSession().save();
            LOG.debug("Previous package removed");
        }
    }
}
