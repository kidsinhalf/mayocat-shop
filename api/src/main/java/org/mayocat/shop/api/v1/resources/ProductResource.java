package org.mayocat.shop.api.v1.resources;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.mayocat.shop.api.v1.reprensentations.AttachmentRepresentation;
import org.mayocat.shop.api.v1.reprensentations.FileRepresentation;
import org.mayocat.shop.api.v1.reprensentations.ProductRepresentation;
import org.mayocat.shop.authorization.annotation.Authorized;
import org.mayocat.shop.model.Attachment;
import org.mayocat.shop.model.Category;
import org.mayocat.shop.model.Product;
import org.mayocat.shop.model.Role;
import org.mayocat.shop.model.reference.EntityReference;
import org.mayocat.shop.rest.annotation.ExistingTenant;
import org.mayocat.shop.rest.representations.EntityReferenceRepresentation;
import org.mayocat.shop.rest.resources.Resource;
import org.mayocat.shop.service.CatalogService;
import org.mayocat.shop.store.EntityAlreadyExistsException;
import org.mayocat.shop.store.EntityDoesNotExistException;
import org.mayocat.shop.store.InvalidEntityException;
import org.mayocat.shop.store.InvalidMoveOperation;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;

import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.sun.jersey.multipart.FormDataBodyPart;
import com.sun.jersey.multipart.FormDataMultiPart;
import com.yammer.metrics.annotation.Timed;

@Component("/api/1.0/product/")
@Path("/api/1.0/product/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ExistingTenant
public class ProductResource extends AbstractAttachmentResource implements Resource
{
    @Inject
    private CatalogService catalogService;

    @Inject
    private Logger logger;

    @GET
    @Timed
    @Authorized
    public List<ProductRepresentation> getProducts(
            @QueryParam("number") @DefaultValue("50") Integer number,
            @QueryParam("offset") @DefaultValue("0") Integer offset,
            @QueryParam("filter") @DefaultValue("") String filter)
    {
        if (filter.equals("uncategorized")) {
            return this.wrapInReprensentations(this.catalogService.findUncategorizedProducts());
        } else {
            return this.wrapInReprensentations(this.catalogService.findAllProducts(number, offset));
        }
    }

    @Path("{slug}")
    @GET
    @Timed
    @Authorized
    public Object getProduct(@PathParam("slug") String slug, @QueryParam("expand") @DefaultValue("") String expand)
    {
        Product product = this.catalogService.findProductBySlug(slug);
        if (product == null) {
            return Response.status(404).build();
        }
        if (!Strings.isNullOrEmpty(expand)) {
            List<Category> categories = this.catalogService.findCategoriesForProduct(product);
            return this.wrapInRepresentation(product, categories);
        } else {
            return this.wrapInRepresentation(product);
        }
    }

    @Path("{slug}/attachment")
    @GET
    public List<AttachmentRepresentation> getAttachments()
    {
        List<AttachmentRepresentation> result = new ArrayList();
        for (Attachment attachment : this.getAttachmentList()) {
            FileRepresentation fr = new FileRepresentation(attachment.getExtension(),
                    "/attachment/" + attachment.getSlug() + "." + attachment.getExtension());
            AttachmentRepresentation representation = new AttachmentRepresentation(
                    "/attachment/" + attachment.getSlug(), attachment.getTitle(), fr);
            result.add(representation);
        }
        return result;
    }

    @Path("{slug}/attachment")
    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response uploadMultipart(@PathParam("slug") String slug, FormDataMultiPart multiPart) throws IOException
    {
        EntityReference
                parent = new EntityReference("product", slug, Optional.<EntityReference>absent());
        List<FormDataBodyPart> fields = multiPart.getFields("files");
        if (fields != null) {
            for (FormDataBodyPart field : fields) {
                String fileName = field.getFormDataContentDisposition().getFileName();
                this.addAttachment(field.getValueAs(InputStream.class), fileName, "", Optional.of(parent));
            }
        } else {
            return Response.status(Response.Status.BAD_REQUEST).entity("No file were found int the request").build();
        }
        // TODO: construct a JSON map with each created attachment URI
        return Response.noContent().build();
    }

    @Path("{slug}/move")
    @POST
    @Timed
    @Authorized
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.WILDCARD)
    public Response move(@PathParam("slug") String slug,
            @FormParam("before") String slugOfProductToMoveBeforeOf,
            @FormParam("after") String slugOfProductToMoveAfterTo)
    {
        try {
            if (!Strings.isNullOrEmpty(slugOfProductToMoveAfterTo)) {
                this.catalogService.moveProduct(slug,
                        slugOfProductToMoveAfterTo, CatalogService.InsertPosition.AFTER);
            } else {
                this.catalogService.moveProduct(slug, slugOfProductToMoveBeforeOf);
            }

            return Response.noContent().build();
        } catch (InvalidMoveOperation e) {
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST)
                    .entity("Invalid move operation").type(MediaType.TEXT_PLAIN_TYPE).build());
        }
    }

    @Path("{slug}")
    @POST
    @Timed
    @Authorized
    public Response updateProduct(@PathParam("slug") String slug,
            Product updatedProduct)
    {
        try {
            Product product = this.catalogService.findProductBySlug(slug);
            if (product == null) {
                return Response.status(404).build();
            } else {
                this.catalogService.updateProduct(updatedProduct);
            }

            return Response.ok().build();
        } catch (InvalidEntityException e) {
            throw new com.yammer.dropwizard.validation.InvalidEntityException(e.getMessage(), e.getErrors());
        } catch (EntityDoesNotExistException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("No product with this slug could be found\n").type(MediaType.TEXT_PLAIN_TYPE).build();
        }
    }

    @Path("{slug}")
    @PUT
    @Timed
    @Authorized
    public Response replaceProduct(@PathParam("slug") String slug, Product newProduct)
    {
        // TODO
        throw new RuntimeException("Not implemented");
    }

    @POST
    @Timed
    @Authorized(roles = { Role.ADMIN })
    public Response createProduct(Product product)
    {
        try {
            Product created = this.catalogService.createProduct(product);

            // TODO : URI factory
            return Response.seeOther(new URI("/product/" + created.getSlug())).build();
        } catch (InvalidEntityException e) {
            throw new com.yammer.dropwizard.validation.InvalidEntityException(e.getMessage(), e.getErrors());
        } catch (EntityAlreadyExistsException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity("A product with this slug already exists\n").type(MediaType.TEXT_PLAIN_TYPE).build();
        } catch (URISyntaxException e) {
            throw new WebApplicationException(e);
        }
    }

    // ////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    private List<ProductRepresentation> wrapInReprensentations(List<Product> products)
    {
        List<ProductRepresentation> result = new ArrayList<ProductRepresentation>();
        for (Product product : products) {
            result.add(this.wrapInRepresentation(product));
        }
        return result;
    }

    private ProductRepresentation wrapInRepresentation(Product product)
    {
        return new ProductRepresentation(product);
    }

    private ProductRepresentation wrapInRepresentation(Product product, List<Category> categories)
    {
        List<EntityReferenceRepresentation> categoriesReferences = Lists.newArrayList();
        for (Category category : categories) {
            categoriesReferences
                    .add(new EntityReferenceRepresentation("/category/" + category.getSlug(), category.getTitle()));
        }
        return new ProductRepresentation(product, categoriesReferences);
    }
}
