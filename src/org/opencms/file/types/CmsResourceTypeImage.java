/*
 * This library is part of OpenCms -
 * the Open Source Content Management System
 *
 * Copyright (c) Alkacon Software GmbH & Co. KG (http://www.alkacon.com)
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * For further information about Alkacon Software GmbH & Co. KG, please see the
 * company website: http://www.alkacon.com
 *
 * For further information about OpenCms, please see the
 * project website: http://www.opencms.org
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package org.opencms.file.types;

import org.opencms.configuration.CmsConfigurationException;
import org.opencms.db.CmsSecurityManager;
import org.opencms.file.CmsFile;
import org.opencms.file.CmsObject;
import org.opencms.file.CmsProperty;
import org.opencms.file.CmsPropertyDefinition;
import org.opencms.file.CmsResource;
import org.opencms.file.CmsResourceFilter;
import org.opencms.file.CmsVfsException;
import org.opencms.loader.CmsDumpLoader;
import org.opencms.loader.CmsImageLoader;
import org.opencms.loader.CmsImageScaler;
import org.opencms.main.CmsException;
import org.opencms.main.CmsLog;
import org.opencms.main.OpenCms;
import org.opencms.security.CmsPermissionSet;
import org.opencms.security.CmsSecurityException;
import org.opencms.util.CmsStringUtil;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.logging.Log;

import org.dom4j.Document;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;

/**
 * Resource type descriptor for the type "image".<p>
 *
 * @since 6.0.0
 */
public class CmsResourceTypeImage extends A_CmsResourceType {

    /**
     * A data container for image size and scale operations.<p>
     */
    protected static class CmsImageAdjuster {

        /** The image byte content. */
        private byte[] m_content;

        /** The (optional) image scaler that contains the image downscale settings. */
        private CmsImageScaler m_imageDownScaler;

        /** The image properties. */
        private List<CmsProperty> m_properties;

        /** The image root path. */
        private String m_rootPath;

        /**
         * Creates a new image data container.<p>
         *
         * @param content the image byte content
         * @param rootPath the image root path
         * @param properties the image properties
         * @param downScaler the (optional) image scaler that contains the image downscale settings
         */
        public CmsImageAdjuster(
            byte[] content,
            String rootPath,
            List<CmsProperty> properties,
            CmsImageScaler downScaler) {

            m_content = content;
            m_rootPath = rootPath;
            m_properties = properties;
            m_imageDownScaler = downScaler;
        }

        /**
         * Calculates the image size and adjusts the image dimensions (if required) accoring to the configured
         * image downscale settings.<p>
         *
         * The image dimensions are always calculated from the given image. The internal list of properties is updated
         * with a value for <code>{@link CmsPropertyDefinition#PROPERTY_IMAGE_SIZE}</code> that
         * contains the calculated image dimensions.<p>
         */
        public void adjust() {

            CmsImageScaler scaler = new CmsImageScaler(getContent(), getRootPath());
            if (!scaler.isValid()) {
                // error calculating image dimensions - this image can't be scaled or resized
                return;
            }

            // check if the image is to big and needs to be rescaled
            if (scaler.isDownScaleRequired(m_imageDownScaler)) {
                // image is to big, perform rescale operation
                CmsImageScaler downScaler = scaler.getDownScaler(m_imageDownScaler);
                // perform the rescale using the adjusted size
                m_content = downScaler.scaleImage(m_content, m_rootPath);
                // image size has been changed, adjust the scaler for later setting of properties
                scaler.setHeight(downScaler.getHeight());
                scaler.setWidth(downScaler.getWidth());
            }

            CmsProperty p = new CmsProperty(CmsPropertyDefinition.PROPERTY_IMAGE_SIZE, null, scaler.toString());
            // create the new property list if required (don't modify the original List)
            List<CmsProperty> result = new ArrayList<CmsProperty>();
            if ((m_properties != null) && (m_properties.size() > 0)) {
                result.addAll(m_properties);
                result.remove(p);
            }
            // add the updated property
            result.add(p);
            // store the changed properties
            m_properties = result;
        }

        /**
         * Returns the image content.<p>
         *
         * @return the image content
         */
        public byte[] getContent() {

            return m_content;
        }

        /**
         * Returns the image properties.<p>
         *
         * @return the image properties
         */
        public List<CmsProperty> getProperties() {

            return m_properties;
        }

        /**
         * Returns the image VFS root path.<p>
         *
         * @return the image VFS root path
         */
        public String getRootPath() {

            return m_rootPath;
        }
    }

    /**
     * Helper class for parsing SVG sizes.<p>
     *
     * Note: This is *not* intended as a general purpose tool, it is only used for parsing SVG sizes
     * as part of the image.size property determination.
     */
    private static class SvgSize {

        /** The numeric value of the size. */
        private double m_size;

        /** The unit of the size. */
        private String m_unit;

        /**
         * Parses the SVG size.<p>
         *
         * @param s the string containing the size
         *
         * @return the parsed size
         */
        public static SvgSize parse(String s) {

            if (CmsStringUtil.isEmptyOrWhitespaceOnly(s)) {
                return null;
            }
            s = s.trim();
            double length = -1;
            int unitPos;
            String unit = "";
            // find longest prefix of s that can be parsed as a number, use the remaining part as the unit
            for (unitPos = s.length(); unitPos >= 0; unitPos--) {
                String prefix = s.substring(0, unitPos);
                unit = s.substring(unitPos);
                try {
                    length = Double.parseDouble(prefix);
                    break;
                } catch (NumberFormatException e) {}
            }
            if (length < 0) {
                LOG.warn("Invalid string for SVG size: " + s);
                return null;
            }
            SvgSize result = new SvgSize();
            result.m_size = length;
            result.m_unit = unit;
            return result;
        }

        /**
         * Gets the numeric value of the size.<p>
         *
         * @return the size
         */
        public double getSize() {

            return m_size;
        }

        /**
         * Gets the unit of the size.<p>
         *
         * @return the unit
         */
        public String getUnit() {

            return m_unit;

        }

        /**
         * @see java.lang.Object#toString()
         */
        @Override
        public String toString() {

            return m_size + m_unit;
        }
    }

    /** The default image preview provider. */
    private static final String GALLERY_PREVIEW_PROVIDER = "org.opencms.ade.galleries.preview.CmsImagePreviewProvider";

    /** The log object for this class. */
    public static final Log LOG = CmsLog.getLog(CmsResourceTypeImage.class);

    /**
     * The value for the {@link CmsPropertyDefinition#PROPERTY_IMAGE_SIZE} property if resources in
     * a folder should never be downscaled.<p>
     */
    public static final String PROPERTY_VALUE_UNLIMITED = "unlimited";

    /** The image scaler for the image downscale operation (if configured). */
    private static CmsImageScaler m_downScaler;

    /** Indicates that the static configuration of the resource type has been frozen. */
    private static boolean m_staticFrozen;

    /** The static resource loader id of this resource type. */
    private static int m_staticLoaderId;

    /** The static type id of this resource type. */
    private static int m_staticTypeId;

    /** The type id of this resource type. */
    private static final int RESOURCE_TYPE_ID = 3;

    /** The name of this resource type. */
    private static final String RESOURCE_TYPE_NAME = "image";

    /**
     * Default constructor, used to initialize member variables.<p>
     */
    public CmsResourceTypeImage() {

        super();
        m_typeId = RESOURCE_TYPE_ID;
        m_typeName = RESOURCE_TYPE_NAME;
    }

    /**
     * Returns the image downscaler to use when writing an image resource to the given root path.<p>
     *
     * If <code>null</code> is returned, image downscaling must not be used for the resource with the given path.
     * This may be the case if image downscaling is not configured at all, or if image downscaling has been disabled
     * for the parent folder by setting the folders property {@link CmsPropertyDefinition#PROPERTY_IMAGE_SIZE}
     * to the value {@link #PROPERTY_VALUE_UNLIMITED}.<p>
     *
     * @param cms the current OpenCms user context
     * @param rootPath the root path of the resource to write
     *
     * @return the downscaler to use, or <code>null</code> if no downscaling is required for the resource
     */
    public static CmsImageScaler getDownScaler(CmsObject cms, String rootPath) {

        if (m_downScaler == null) {
            // downscaling is not configured at all
            return null;
        }
        // try to read the image.size property from the parent folder
        String parentFolder = CmsResource.getParentFolder(rootPath);
        parentFolder = cms.getRequestContext().removeSiteRoot(parentFolder);
        try {
            CmsProperty fileSizeProperty = cms.readPropertyObject(
                parentFolder,
                CmsPropertyDefinition.PROPERTY_IMAGE_SIZE,
                false);
            if (!fileSizeProperty.isNullProperty()) {
                // image.size property has been set
                String value = fileSizeProperty.getValue().trim();
                if (CmsStringUtil.isNotEmpty(value)) {
                    if (PROPERTY_VALUE_UNLIMITED.equals(value)) {
                        // in this case no downscaling must be done
                        return null;
                    } else {
                        CmsImageScaler scaler = new CmsImageScaler(value);
                        if (scaler.isValid()) {
                            // special folder based scaler settings have been set
                            return scaler;
                        }
                    }
                }
            }
        } catch (CmsException e) {
            // ignore, continue with given downScaler
        }
        return (CmsImageScaler)m_downScaler.clone();
    }

    /**
     * Returns the static type id of this (default) resource type.<p>
     *
     * @return the static type id of this (default) resource type
     */
    public static int getStaticTypeId() {

        return m_staticTypeId;
    }

    /**
     * Returns the static type name of this (default) resource type.<p>
     *
     * @return the static type name of this (default) resource type
     */
    public static String getStaticTypeName() {

        return RESOURCE_TYPE_NAME;
    }

    /**
     * @see org.opencms.file.types.I_CmsResourceType#createResource(org.opencms.file.CmsObject, org.opencms.db.CmsSecurityManager, java.lang.String, byte[], java.util.List)
     */
    @Override
    public CmsResource createResource(
        CmsObject cms,
        CmsSecurityManager securityManager,
        String resourcename,
        byte[] content,
        List<CmsProperty> properties)
    throws CmsException {

        if (resourcename.toLowerCase().endsWith(".svg")) {
            List<CmsProperty> prop2 = tryAddImageSizeFromSvg(content, properties);
            properties = prop2;
        } else if (CmsImageLoader.isEnabled()) {
            String rootPath = cms.getRequestContext().addSiteRoot(resourcename);
            // get the downscaler to use
            CmsImageScaler downScaler = getDownScaler(cms, rootPath);
            // create a new image scale adjuster
            CmsImageAdjuster adjuster = new CmsImageAdjuster(content, rootPath, properties, downScaler);
            // update the image scale adjuster - this will calculate the image dimensions and (optionally) downscale the size
            adjuster.adjust();
            // continue with the updated content and properties
            content = adjuster.getContent();
            properties = adjuster.getProperties();
        }
        return super.createResource(cms, securityManager, resourcename, content, properties);
    }

    /**
     * @see org.opencms.file.types.I_CmsResourceType#getGalleryPreviewProvider()
     */
    @Override
    public String getGalleryPreviewProvider() {

        if (m_galleryPreviewProvider == null) {
            m_galleryPreviewProvider = getConfiguration().getString(
                CONFIGURATION_GALLERY_PREVIEW_PROVIDER,
                GALLERY_PREVIEW_PROVIDER);
        }
        return m_galleryPreviewProvider;
    }

    /**
     * @see org.opencms.file.types.I_CmsResourceType#getLoaderId()
     */
    @Override
    public int getLoaderId() {

        return m_staticLoaderId;
    }

    /**
     * @see org.opencms.file.types.I_CmsResourceType#importResource(org.opencms.file.CmsObject, org.opencms.db.CmsSecurityManager, java.lang.String, org.opencms.file.CmsResource, byte[], java.util.List)
     */
    @Override
    public CmsResource importResource(
        CmsObject cms,
        CmsSecurityManager securityManager,
        String resourcename,
        CmsResource resource,
        byte[] content,
        List<CmsProperty> properties)
    throws CmsException {

        if (resourcename.toLowerCase().endsWith(".svg")) {
            properties = tryAddImageSizeFromSvg(content, properties);
        } else if (CmsImageLoader.isEnabled()) {
            // siblings have null content in import
            if (content != null) {
                // get the downscaler to use
                CmsImageScaler downScaler = getDownScaler(cms, resource.getRootPath());
                // create a new image scale adjuster
                CmsImageAdjuster adjuster = new CmsImageAdjuster(
                    content,
                    resource.getRootPath(),
                    properties,
                    downScaler);
                // update the image scale adjuster - this will calculate the image dimensions and (optionally) adjust the size
                adjuster.adjust();
                // continue with the updated content and properties
                content = adjuster.getContent();
                properties = adjuster.getProperties();
            }
        }
        return super.importResource(cms, securityManager, resourcename, resource, content, properties);
    }

    /**
     * @see org.opencms.file.types.A_CmsResourceType#initConfiguration(java.lang.String, java.lang.String, String)
     */
    @Override
    public void initConfiguration(String name, String id, String className) throws CmsConfigurationException {

        if ((OpenCms.getRunLevel() > OpenCms.RUNLEVEL_2_INITIALIZING) && m_staticFrozen) {
            // configuration already frozen
            throw new CmsConfigurationException(
                Messages.get().container(
                    Messages.ERR_CONFIG_FROZEN_3,
                    this.getClass().getName(),
                    getStaticTypeName(),
                    new Integer(getStaticTypeId())));
        }

        if (!RESOURCE_TYPE_NAME.equals(name)) {
            // default resource type MUST have default name
            throw new CmsConfigurationException(
                Messages.get().container(
                    Messages.ERR_INVALID_RESTYPE_CONFIG_NAME_3,
                    this.getClass().getName(),
                    RESOURCE_TYPE_NAME,
                    name));
        }

        // freeze the configuration
        m_staticFrozen = true;

        super.initConfiguration(RESOURCE_TYPE_NAME, id, className);
        // set static members with values from the configuration
        m_staticTypeId = m_typeId;

        if (CmsImageLoader.isEnabled()) {
            // the image loader is enabled, image operations are supported
            m_staticLoaderId = CmsImageLoader.RESOURCE_LOADER_ID_IMAGE_LOADER;
            // set the maximum size scaler
            String downScaleParams = CmsImageLoader.getDownScaleParams();
            if (CmsStringUtil.isNotEmptyOrWhitespaceOnly(downScaleParams)) {
                m_downScaler = new CmsImageScaler(downScaleParams);
                if (!m_downScaler.isValid()) {
                    // ignore invalid parameters
                    m_downScaler = null;
                }
            }
        } else {
            // no image operations are supported, use dump loader
            m_staticLoaderId = CmsDumpLoader.RESOURCE_LOADER_ID;
            // disable maximum image size operation
            m_downScaler = null;
        }
    }

    /**
     * @see org.opencms.file.types.I_CmsResourceType#replaceResource(org.opencms.file.CmsObject, org.opencms.db.CmsSecurityManager, org.opencms.file.CmsResource, int, byte[], java.util.List)
     */
    @Override
    public void replaceResource(
        CmsObject cms,
        CmsSecurityManager securityManager,
        CmsResource resource,
        int type,
        byte[] content,
        List<CmsProperty> properties)
    throws CmsException {

        if (resource.getRootPath().toLowerCase().endsWith(".svg")) {
            List<CmsProperty> newProperties = tryAddImageSizeFromSvg(content, properties);
            if (properties != newProperties) { // yes, we actually do want to compare object identity here
                writePropertyObjects(cms, securityManager, resource, newProperties);
            }
        } else if (CmsImageLoader.isEnabled()) {
            // check if the user has write access and if resource is locked
            // done here so that no image operations are performed in case no write access is granted
            securityManager.checkPermissions(
                cms.getRequestContext(),
                resource,
                CmsPermissionSet.ACCESS_WRITE,
                true,
                CmsResourceFilter.ALL);

            // get the downscaler to use
            CmsImageScaler downScaler = getDownScaler(cms, resource.getRootPath());
            // create a new image scale adjuster
            CmsImageAdjuster adjuster = new CmsImageAdjuster(content, resource.getRootPath(), properties, downScaler);
            // update the image scale adjuster - this will calculate the image dimensions and (optionally) adjust the size
            adjuster.adjust();
            // continue with the updated content
            content = adjuster.getContent();
            if (adjuster.getProperties() != null) {
                // write properties
                writePropertyObjects(cms, securityManager, resource, adjuster.getProperties());
            }
        }
        super.replaceResource(cms, securityManager, resource, type, content, properties);
    }

    /**
     * @see org.opencms.file.types.I_CmsResourceType#writeFile(org.opencms.file.CmsObject, org.opencms.db.CmsSecurityManager, org.opencms.file.CmsFile)
     */
    @Override
    public CmsFile writeFile(CmsObject cms, CmsSecurityManager securityManager, CmsFile resource)
    throws CmsException, CmsVfsException, CmsSecurityException {

        if (CmsImageLoader.isEnabled()) {
            // check if the user has write access and if resource is locked
            // done here so that no image operations are performed in case no write access is granted
            securityManager.checkPermissions(
                cms.getRequestContext(),
                resource,
                CmsPermissionSet.ACCESS_WRITE,
                true,
                CmsResourceFilter.ALL);

            // get the downscaler to use
            CmsImageScaler downScaler = getDownScaler(cms, resource.getRootPath());
            // create a new image scale adjuster
            CmsImageAdjuster adjuster = new CmsImageAdjuster(
                resource.getContents(),
                resource.getRootPath(),
                null,
                downScaler);
            // update the image scale adjuster - this will calculate the image dimensions and (optionally) adjust the size
            adjuster.adjust();
            // continue with the updated content
            resource.setContents(adjuster.getContent());
            if (adjuster.getProperties() != null) {
                // write properties
                writePropertyObjects(cms, securityManager, resource, adjuster.getProperties());
            }
        }
        return super.writeFile(cms, securityManager, resource);
    }

    /**
     * Tries to use the viewbox from the SVG data to determine the image size and add it to the list of properties.<p>
     *
     * @param content the content bytes of an SVG file
     * @param properties the original properties (this object will not be modified)
     *
     * @return the amended properties
     */
    protected List<CmsProperty> tryAddImageSizeFromSvg(byte[] content, List<CmsProperty> properties) {

        if ((content == null) || (content.length == 0)) {
            return properties;
        }
        List<CmsProperty> newProps = properties;
        try {
            double w = -1, h = -1;
            SAXReader reader = new SAXReader();
            Document doc = reader.read(new ByteArrayInputStream(content));
            Element node = (Element)(doc.selectSingleNode("/svg"));
            if (node != null) {
                String widthStr = node.attributeValue("width");
                String heightStr = node.attributeValue("height");
                SvgSize width = SvgSize.parse(widthStr);
                SvgSize height = SvgSize.parse(heightStr);
                if ((width != null) && (height != null) && Objects.equals(width.getUnit(), height.getUnit())) {
                    // If width and height are given and have the same units, just interpret them as pixels, otherwise use viewbox
                    w = width.getSize();
                    h = height.getSize();
                } else {
                    String viewboxStr = node.attributeValue("viewBox");
                    if (viewboxStr != null) {
                        viewboxStr = viewboxStr.replace(",", " ");
                        String[] viewboxParts = viewboxStr.trim().split(" +");
                        if (viewboxParts.length == 4) {
                            w = Double.parseDouble(viewboxParts[2]);
                            h = Double.parseDouble(viewboxParts[3]);
                        }
                    }
                }
                if ((w > 0) && (h > 0)) {
                    String propValue = "w:" + (int)Math.round(w) + ",h:" + (int)Math.round(h);
                    Map<String, CmsProperty> propsMap = properties == null
                    ? new HashMap<>()
                    : CmsProperty.toObjectMap(properties);
                    propsMap.put(
                        CmsPropertyDefinition.PROPERTY_IMAGE_SIZE,
                        new CmsProperty(CmsPropertyDefinition.PROPERTY_IMAGE_SIZE, null, propValue));
                    newProps = new ArrayList<>(propsMap.values());
                }

            }
        } catch (Exception e) {
            LOG.error("Error while trying to determine size of SVG: " + e.getLocalizedMessage(), e);
        }
        return newProps;
    }

}