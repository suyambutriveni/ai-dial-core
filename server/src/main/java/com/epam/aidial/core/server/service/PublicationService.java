package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.controller.ApplicationUtil;
import com.epam.aidial.core.server.data.ListPublishedResourcesRequest;
import com.epam.aidial.core.server.data.MetadataBase;
import com.epam.aidial.core.server.data.Notification;
import com.epam.aidial.core.server.data.Publication;
import com.epam.aidial.core.server.data.RejectPublicationRequest;
import com.epam.aidial.core.server.data.ResourceFolderMetadata;
import com.epam.aidial.core.server.data.ResourceItemMetadata;
import com.epam.aidial.core.server.data.ResourceType;
import com.epam.aidial.core.server.data.ResourceUrl;
import com.epam.aidial.core.server.data.Rule;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.storage.BlobStorageUtil;
import com.epam.aidial.core.server.storage.ResourceDescription;
import com.epam.aidial.core.server.util.EtagHeader;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.UrlUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.mutable.MutableObject;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

@RequiredArgsConstructor
public class PublicationService {

    private static final String PUBLICATIONS_NAME = "publications";

    private static final TypeReference<Map<String, Publication>> PUBLICATIONS_TYPE = new TypeReference<>() {
    };

    private static final ResourceDescription PUBLIC_PUBLICATIONS = ResourceDescription.fromDecoded(
            ResourceType.PUBLICATION, BlobStorageUtil.PUBLIC_BUCKET, BlobStorageUtil.PUBLIC_LOCATION, PUBLICATIONS_NAME);

    private static final Set<ResourceType> ALLOWED_RESOURCES = Set.of(ResourceType.FILE, ResourceType.CONVERSATION,
            ResourceType.PROMPT, ResourceType.APPLICATION);

    private final EncryptionService encryption;
    private final ResourceService resourceService;
    private final AccessService accessService;
    private final RuleService ruleService;
    private final NotificationService notificationService;
    private final ApplicationService applicationService;
    private final Supplier<String> ids;
    private final LongSupplier clock;

    public static boolean isReviewBucket(ResourceDescription resource) {
        return resource.isPrivate() && resource.getBucketLocation().contains(PUBLICATIONS_NAME);
    }

    public static boolean hasReviewAccess(ProxyContext context, ResourceDescription resource) {
        if (isReviewBucket(resource)) {
            String location = BlobStorageUtil.buildInitiatorBucket(context);
            String reviewLocation = location + PUBLICATIONS_NAME + BlobStorageUtil.PATH_SEPARATOR;
            return resource.getBucketLocation().startsWith(reviewLocation);
        }

        return false;
    }

    public Collection<Publication> listPublications(ResourceDescription resource) {
        if (resource.getType() != ResourceType.PUBLICATION || !resource.isRootFolder()) {
            throw new IllegalArgumentException("Bad publication url: " + resource.getUrl());
        }

        ResourceDescription key = publications(resource);
        Map<String, Publication> publications = decodePublications(resourceService.getResource(key));

        for (Publication publication : publications.values()) {
            leaveMetadata(publication);
        }

        return publications.values();
    }

    public Collection<MetadataBase> listPublishedResources(ListPublishedResourcesRequest request, String bucket, String location) {
        ResourceDescription publicationResource = publications(bucket, location);
        Map<String, Publication> publications = decodePublications(resourceService.getResource(publicationResource));

        // get approved publications only
        List<Publication> approvedPublications = publications.values()
                .stream()
                .filter(publication -> Publication.Status.APPROVED.equals(publication.getStatus()))
                .toList();

        Set<Publication.Resource> resourceSet = approvedPublications.stream()
                .flatMap(publication -> publication.getResources().stream())
                .collect(Collectors.toSet());
        Set<ResourceType> requestedResourceTypes = request.getResourceTypes();

        Set<MetadataBase> metadata = new HashSet<>();
        for (Publication.Resource resource : resourceSet) {
            ResourceDescription resourceDescription = ResourceDescription.fromPrivateUrl(resource.getSourceUrl(), encryption);
            // check if published resource match requested criteria
            if (!requestedResourceTypes.contains(resourceDescription.getType())) {
                continue;
            }

            if (resourceDescription.isFolder()) {
                metadata.add(new ResourceFolderMetadata(resourceDescription));
            } else {
                metadata.add(new ResourceItemMetadata(resourceDescription));
            }
        }

        return metadata;
    }

    public Publication getPublication(ResourceDescription resource) {
        if (resource.getType() != ResourceType.PUBLICATION || resource.isPublic() || resource.isFolder() || resource.getParentPath() != null) {
            throw new IllegalArgumentException("Bad publication url: " + resource.getUrl());
        }

        ResourceDescription key = publications(resource);
        Map<String, Publication> publications = decodePublications(resourceService.getResource(key));
        Publication publication = publications.get(resource.getUrl());

        if (publication == null) {
            throw new ResourceNotFoundException("No publication: " + resource.getUrl());
        }

        return publication;
    }

    public Publication createPublication(ProxyContext context, Publication publication) {
        String bucketLocation = BlobStorageUtil.buildInitiatorBucket(context);
        String bucket = encryption.encrypt(bucketLocation);
        boolean isAdmin = accessService.hasAdminAccess(context);

        prepareAndValidatePublicationRequest(context, publication, bucket, bucketLocation, isAdmin);

        List<Publication.Resource> resourcesToAdd = publication.getResources().stream()
                .filter(resource -> resource.getAction() == Publication.ResourceAction.ADD)
                .toList();

        copySourceToReviewResources(resourcesToAdd);

        resourceService.computeResource(publications(bucket, bucketLocation), body -> {
            Map<String, Publication> publications = decodePublications(body);

            if (publications.put(publication.getUrl(), publication) != null) {
                throw new IllegalStateException("Publication with such url already exists: " + publication.getUrl());
            }

            return encodePublications(publications);
        });

        resourceService.computeResource(PUBLIC_PUBLICATIONS, body -> {
            Map<String, Publication> publications = decodePublications(body);

            if (publications.put(publication.getUrl(), newMetadata(publication)) != null) {
                throw new IllegalStateException("Publication with such url already exists: " + publication.getUrl());
            }

            return encodePublications(publications);
        });

        return publication;
    }

    public Publication deletePublication(ResourceDescription resource) {
        if (resource.getType() != ResourceType.PUBLICATION || resource.isPublic() || resource.isFolder() || resource.getParentPath() != null) {
            throw new IllegalArgumentException("Bad publication url: " + resource.getUrl());
        }

        resourceService.computeResource(PUBLIC_PUBLICATIONS, body -> {
            Map<String, Publication> publications = decodePublications(body);
            Publication publication = publications.remove(resource.getUrl());
            return (publication == null) ? body : encodePublications(publications);
        });

        MutableObject<Publication> reference = new MutableObject<>();
        resourceService.computeResource(publications(resource), body -> {
            Map<String, Publication> publications = decodePublications(body);
            Publication publication = publications.remove(resource.getUrl());

            if (publication == null) {
                throw new ResourceNotFoundException("No publication: " + resource.getUrl());
            }

            reference.setValue(publication);
            return encodePublications(publications);
        });

        Publication publication = reference.getValue();

        if (publication.getStatus() == Publication.Status.PENDING) {
            List<Publication.Resource> resourcesToAdd = publication.getResources().stream()
                    .filter(i -> i.getAction() == Publication.ResourceAction.ADD)
                    .toList();
            deleteReviewResources(resourcesToAdd);
        }

        return publication;
    }

    @Nullable
    public Publication approvePublication(ResourceDescription resource) {
        Publication publication = getPublication(resource);
        if (publication.getStatus() != Publication.Status.PENDING) {
            throw new ResourceNotFoundException("Publication is already finalized: " + resource.getUrl());
        }

        List<Publication.Resource> resourcesToAdd = publication.getResources().stream()
                .filter(i -> i.getAction() == Publication.ResourceAction.ADD)
                .toList();

        List<Publication.Resource> resourcesToDelete = publication.getResources().stream()
                .filter(i -> i.getAction() == Publication.ResourceAction.DELETE)
                .toList();

        checkReviewResources(resourcesToAdd);
        checkTargetResources(resourcesToAdd, false);
        checkTargetResources(resourcesToDelete, true);

        resourceService.computeResource(publications(resource), body -> {
            Map<String, Publication> publications = decodePublications(body);
            Publication previous = publications.put(resource.getUrl(), publication);

            if (!publication.equals(previous)) {
                throw new ResourceNotFoundException("Publication changed during approving: " + resource.getUrl());
            }

            publication.setStatus(Publication.Status.APPROVED);
            return encodePublications(publications);
        });

        resourceService.computeResource(PUBLIC_PUBLICATIONS, body -> {
            Map<String, Publication> publications = decodePublications(body);
            Publication removed = publications.remove(resource.getUrl());
            return (removed == null) ? body : encodePublications(publications);
        });

        ruleService.storeRules(publication);

        copyReviewToTargetResources(resourcesToAdd);
        deleteReviewResources(resourcesToAdd);
        deletePublicResources(resourcesToDelete);

        String notificationMessage = "Your request has been approved by admin";
        Notification notification = Notification.getPublicationNotification(resource.getUrl(), notificationMessage);
        notificationService.createNotification(resource.getBucketName(), resource.getBucketLocation(), notification);

        return publication;
    }

    @Nullable
    public Publication rejectPublication(ResourceDescription resource, RejectPublicationRequest request) {
        if (resource.isFolder() || resource.isPublic() || resource.getParentPath() != null) {
            throw new IllegalArgumentException("Bad publication url: " + resource.getUrl());
        }

        MutableObject<Publication> reference = new MutableObject<>();
        resourceService.computeResource(publications(resource), body -> {
            Map<String, Publication> publications = decodePublications(body);
            Publication publication = publications.get(resource.getUrl());

            if (publication == null) {
                throw new ResourceNotFoundException("No publication: " + resource.getUrl());
            }

            if (publication.getStatus() != Publication.Status.PENDING) {
                throw new ResourceNotFoundException("Publication is already finalized: " + resource.getUrl());
            }

            reference.setValue(publication);
            publication.setStatus(Publication.Status.REJECTED);
            return encodePublications(publications);
        });

        resourceService.computeResource(PUBLIC_PUBLICATIONS, body -> {
            Map<String, Publication> publications = decodePublications(body);
            Publication publication = publications.remove(resource.getUrl());
            return (publication == null) ? body : encodePublications(publications);
        });

        Publication publication = reference.getValue();
        List<Publication.Resource> resourcesToAdd = publication.getResources().stream()
                .filter(i -> i.getAction() == Publication.ResourceAction.ADD)
                .toList();
        deleteReviewResources(resourcesToAdd);

        String rejectReason = request.comment();
        String notificationMessage = "Your request has been rejected by admin";
        notificationMessage = rejectReason != null ? notificationMessage + ": " + rejectReason : notificationMessage;
        Notification notification = Notification.getPublicationNotification(resource.getUrl(), notificationMessage);
        notificationService.createNotification(resource.getBucketName(), resource.getBucketLocation(), notification);

        return publication;
    }

    private void prepareAndValidatePublicationRequest(ProxyContext context, Publication publication,
                                                      String bucketName, String bucketLocation,
                                                      boolean isAdmin) {
        String targetFolder = publication.getTargetFolder();
        if (targetFolder == null) {
            throw new IllegalArgumentException("Publication \"targetFolder\" is missing");
        }

        // rules to the root publication folder are not allowed
        if (targetFolder.equals("public/") && publication.getRules() != null && !publication.getRules().isEmpty()) {
            throw new IllegalArgumentException("Rules are not allowed for root targetFolder");
        }

        // publication must contain resources or rule or both
        if ((publication.getResources() == null || publication.getResources().isEmpty()) && publication.getRules() == null) {
            throw new IllegalArgumentException("Publication must have at least one resource or rule");
        }

        ResourceUrl targetFolderUrl = ResourceUrl.parse(publication.getTargetFolder());

        if (!targetFolderUrl.startsWith(BlobStorageUtil.PUBLIC_BUCKET) || !targetFolderUrl.isFolder()) {
            throw new IllegalArgumentException("Publication \"targetUrl\" must start with: %s and ends with: %s"
                    .formatted(BlobStorageUtil.PUBLIC_BUCKET, BlobStorageUtil.PATH_SEPARATOR));
        }

        String id = UrlUtil.encodePathSegment(ids.get());
        String publicationUrl = String.join(BlobStorageUtil.PATH_SEPARATOR, "publications", bucketName, id);
        String reviewBucket = encodeReviewBucket(bucketLocation, id);
        targetFolder = targetFolderUrl.getUrl();

        publication.setUrl(publicationUrl);
        publication.setTargetFolder(targetFolder);
        publication.setCreatedAt(clock.getAsLong());
        publication.setStatus(Publication.Status.PENDING);

        Set<String> urls = new HashSet<>();
        for (Publication.Resource resource : publication.getResources()) {
            Publication.ResourceAction action = resource.getAction();
            if (action == null) {
                throw new IllegalArgumentException("Resource \"action\" is missing");
            }

            if (action == Publication.ResourceAction.ADD) {
                validateResourceForAddition(context, resource, targetFolder, reviewBucket, urls);
            } else if (action == Publication.ResourceAction.DELETE) {
                validateResourceForDeletion(resource, targetFolder, urls, bucketName, isAdmin);
            } else {
                throw new UnsupportedOperationException("Unsupported resource action: " + action);
            }
        }

        Set<ResourceDescription> targetResources = publication.getResources().stream()
                .map(resource -> ResourceDescription.fromPublicUrl(resource.getTargetUrl()))
                .collect(Collectors.toUnmodifiableSet());

        // validate if user has access to all target resources
        boolean hasPublicAccess = accessService.hasPublicAccess(targetResources, context);
        if (!hasPublicAccess) {
            throw new PermissionDeniedException("User don't have permissions to the provided target resources");
        }

        validateRules(publication);
    }

    private void validateResourceForAddition(ProxyContext context, Publication.Resource resource, String targetFolder,
                                             String reviewBucket, Set<String> urls) {
        ResourceDescription source = ResourceDescription.fromPrivateUrl(resource.getSourceUrl(), encryption);
        ResourceDescription target = ResourceDescription.fromPublicUrl(resource.getTargetUrl());
        verifyResourceType(source);

        String sourceUrl = source.getUrl();
        String targetUrl = target.getUrl();

        if (!accessService.hasReadAccess(source, context)) {
            throw new PermissionDeniedException("You don't have permission to access resource " + sourceUrl);
        }

        if (source.isFolder()) {
            throw new IllegalArgumentException("Source resource is folder: " + sourceUrl);
        }

        if (target.isFolder()) {
            throw new IllegalArgumentException("Target resource is folder: " + targetUrl);
        }

        if (source.getType() != target.getType()) {
            throw new IllegalArgumentException("Source and target resource types do not match: " + targetUrl);
        }

        String targetSuffix = targetUrl.substring(source.getType().getGroup().length() + 1);

        if (!targetSuffix.startsWith(targetFolder)) {
            throw new IllegalArgumentException("Target resource folder does not match with target folder: " + targetUrl);
        } else {
            targetSuffix = targetSuffix.substring(targetFolder.length());
        }

        if (!resourceService.hasResource(source)) {
            throw new IllegalArgumentException("Source resource does not exists: " + sourceUrl);
        }

        if (resourceService.hasResource(target)) {
            throw new IllegalArgumentException("Target resource already exists: " + targetUrl);
        }

        String reviewUrl = source.getType().getGroup() + BlobStorageUtil.PATH_SEPARATOR
                + reviewBucket + BlobStorageUtil.PATH_SEPARATOR + targetSuffix;

        if (!urls.add(sourceUrl)) {
            throw new IllegalArgumentException("Source resources have duplicate urls: " + sourceUrl);
        }

        if (!urls.add(targetUrl)) {
            throw new IllegalArgumentException("Target resources have duplicate urls: " + targetUrl);
        }

        if (!urls.add(reviewUrl)) {
            throw new IllegalArgumentException("Review resources have duplicate urls: " + reviewUrl);
        }

        resource.setSourceUrl(sourceUrl);
        resource.setTargetUrl(targetUrl);
        resource.setReviewUrl(reviewUrl);
    }

    private void validateResourceForDeletion(Publication.Resource resource, String targetFolder, Set<String> urls,
                                             String bucketName, boolean isAdmin) {
        String targetUrl = resource.getTargetUrl();
        ResourceDescription target = ResourceDescription.fromPublicUrl(targetUrl);
        verifyResourceType(target);

        if (target.isFolder()) {
            throw new IllegalArgumentException("Target resource is folder: " + targetUrl);
        }

        String targetSuffix = targetUrl.substring(target.getType().getGroup().length() + 1);
        if (!targetSuffix.startsWith(targetFolder)) {
            throw new IllegalArgumentException("Target resource folder does not match with target folder: " + targetUrl);
        }

        if (!urls.add(targetUrl)) {
            throw new IllegalArgumentException("Target resources have duplicate urls: " + targetUrl);
        }

        if (!resourceService.hasResource(target)) {
            throw new IllegalArgumentException("Target resource does not exists: " + targetUrl);
        }

        if (target.getType() == ResourceType.APPLICATION && !isAdmin) {
            Application application = applicationService.getApplication(target).getValue();
            if (application.getFunction() != null && !application.getFunction().getAuthorBucket().equals(bucketName)) {
                throw new IllegalArgumentException("Target application has a different author: " + targetUrl);
            }
        }

        resource.setTargetUrl(targetUrl);
    }

    private void validateRules(Publication publication) {
        if (publication.getRules() != null) {
            for (Rule rule : publication.getRules()) {
                Rule.Function function = rule.getFunction();
                if (function == null) {
                    throw new IllegalArgumentException("Rule does not have function");
                }

                if (rule.getSource() == null) {
                    throw new IllegalArgumentException("Rule does not have source");
                }

                // function TRUE or FALSE do not require targets
                if (function != Rule.Function.TRUE && function != Rule.Function.FALSE) {
                    if (rule.getTargets() == null || rule.getTargets().isEmpty()) {
                        throw new IllegalArgumentException("Rule %s does not have targets".formatted(function));
                    }
                }
            }
        }
    }

    private void checkReviewResources(List<Publication.Resource> resources) {
        for (Publication.Resource resource : resources) {
            String url = resource.getReviewUrl();
            ResourceDescription descriptor = ResourceDescription.fromPrivateUrl(url, encryption);
            verifyResourceType(descriptor);
            if (!resourceService.hasResource(descriptor)) {
                throw new IllegalArgumentException("Review resource does not exist: " + descriptor.getUrl());
            }
        }
    }

    private void checkTargetResources(List<Publication.Resource> resources, boolean exists) {
        for (Publication.Resource resource : resources) {
            String url = resource.getTargetUrl();
            ResourceDescription descriptor = ResourceDescription.fromPublicUrl(url);
            verifyResourceType(descriptor);

            if (resourceService.hasResource(descriptor) != exists) {
                String errorMessage = exists ? "Target resource does not exists: " + url : "Target resource  exists: " + url;
                throw new IllegalArgumentException(errorMessage);
            }
        }
    }

    private void copySourceToReviewResources(List<Publication.Resource> resources) {
        Map<String, String> replacementLinks = new HashMap<>();

        for (Publication.Resource resource : resources) {
            String sourceUrl = resource.getSourceUrl();
            String reviewUrl = resource.getReviewUrl();

            ResourceDescription from = ResourceDescription.fromPrivateUrl(sourceUrl, encryption);
            ResourceDescription to = ResourceDescription.fromPrivateUrl(reviewUrl, encryption);

            verifyResourceType(from);

            if (from.getType() == ResourceType.FILE) {
                String decodedUrl = UrlUtil.decodePath(from.getUrl());
                replacementLinks.put(decodedUrl, to.getUrl());
            }
        }

        for (Publication.Resource resource : resources) {
            String sourceUrl = resource.getSourceUrl();
            String reviewUrl = resource.getReviewUrl();

            ResourceDescription from = ResourceDescription.fromPrivateUrl(sourceUrl, encryption);
            ResourceDescription to = ResourceDescription.fromPrivateUrl(reviewUrl, encryption);

            if (from.getType() == ResourceType.APPLICATION) {
                applicationService.copyApplication(from, to, false, app -> {
                    app.setReference(ApplicationUtil.generateReference());
                    app.setIconUrl(replaceLink(replacementLinks, app.getIconUrl()));
                });
            } else if (!resourceService.copyResource(from, to)) {
                throw new IllegalStateException("Can't copy source resource from: " + from.getUrl() + " to review: " + to.getUrl());
            }

            if (from.getType() == ResourceType.CONVERSATION) {
                this.resourceService.computeResource(to, body -> PublicationUtil.replaceConversationLinks(body, to, replacementLinks));
            }
        }
    }

    private void copyReviewToTargetResources(List<Publication.Resource> resources) {
        Map<String, String> replacementLinks = new HashMap<>();

        for (Publication.Resource resource : resources) {
            String reviewUrl = resource.getReviewUrl();
            String targetUrl = resource.getTargetUrl();

            ResourceDescription from = ResourceDescription.fromPrivateUrl(reviewUrl, encryption);
            ResourceDescription to = ResourceDescription.fromPublicUrl(targetUrl);

            verifyResourceType(from);

            if (from.getType() == ResourceType.FILE) {
                String decodedUrl = UrlUtil.decodePath(from.getUrl());
                replacementLinks.put(decodedUrl, to.getUrl());
            }
        }

        for (Publication.Resource resource : resources) {
            String reviewUrl = resource.getReviewUrl();
            String targetUrl = resource.getTargetUrl();

            ResourceDescription from = ResourceDescription.fromPrivateUrl(reviewUrl, encryption);
            ResourceDescription to = ResourceDescription.fromPublicUrl(targetUrl);

            if (from.getType() == ResourceType.APPLICATION) {
                applicationService.copyApplication(from, to, false, app -> {
                    app.setReference(ApplicationUtil.generateReference());
                    app.setIconUrl(replaceLink(replacementLinks, app.getIconUrl()));
                });
            } else if (!resourceService.copyResource(from, to)) {
                throw new IllegalStateException("Can't copy source resource from: " + from.getUrl() + " to review: " + to.getUrl());
            }

            if (from.getType() == ResourceType.CONVERSATION) {
                resourceService.computeResource(to, body -> PublicationUtil.replaceConversationLinks(body, to, replacementLinks));
            }
        }
    }

    private void deleteReviewResources(List<Publication.Resource> resources) {
        for (Publication.Resource resource : resources) {
            String url = resource.getReviewUrl();
            ResourceDescription descriptor = ResourceDescription.fromPrivateUrl(url, encryption);
            verifyResourceType(descriptor);
            resourceService.deleteResource(descriptor, EtagHeader.ANY);
        }
    }

    private void deletePublicResources(List<Publication.Resource> resources) {
        for (Publication.Resource resource : resources) {
            String url = resource.getTargetUrl();
            ResourceDescription descriptor = ResourceDescription.fromPublicUrl(url);
            verifyResourceType(descriptor);
            resourceService.deleteResource(descriptor, EtagHeader.ANY);
        }
    }

    private void verifyResourceType(ResourceDescription descriptor) {
        if (!ALLOWED_RESOURCES.contains(descriptor.getType())) {
            throw new IllegalStateException("Unsupported type: " + descriptor.getType());
        }
    }

    private String encodeReviewBucket(String bucketLocation, String id) {
        String path = bucketLocation
                + PUBLICATIONS_NAME + BlobStorageUtil.PATH_SEPARATOR
                + id + BlobStorageUtil.PATH_SEPARATOR;

        return encryption.encrypt(path);
    }

    /**
     * Leaves only required fields for listing.
     */
    private static void leaveMetadata(Publication publication) {
        publication.setResources(null).setRules(null);
    }

    private static Publication newMetadata(Publication publication) {
        return new Publication()
                .setUrl(publication.getUrl())
                .setName(publication.getName())
                .setTargetFolder(publication.getTargetFolder())
                .setStatus(publication.getStatus())
                .setResourceTypes(publication.getResourceTypes())
                .setCreatedAt(publication.getCreatedAt());
    }

    private static ResourceDescription publications(ResourceDescription resource) {
        return publications(resource.getBucketName(), resource.getBucketLocation());
    }

    private static ResourceDescription publications(String bucket, String location) {
        return ResourceDescription.fromDecoded(ResourceType.PUBLICATION,
                bucket, location, PUBLICATIONS_NAME);
    }

    private static Map<String, Publication> decodePublications(String json) {
        Map<String, Publication> publications = ProxyUtil.convertToObject(json, PUBLICATIONS_TYPE);
        return (publications == null) ? new LinkedHashMap<>() : publications;
    }

    private static String encodePublications(Map<String, Publication> publications) {
        return ProxyUtil.convertToString(publications);
    }

    private static String replaceLink(Map<String, String> links, String url) {
        if (url != null) {
            String key = UrlUtil.decodePath(url);
            String replacement = links.get(key);

            if (replacement != null) {
                return replacement;
            }
        }

        return url;
    }
}