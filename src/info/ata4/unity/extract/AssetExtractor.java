/*
 ** 2013 June 16
 **
 ** The author disclaims copyright to this source code.  In place of
 ** a legal notice, here is a blessing:
 **    May you do good and not evil.
 **    May you find forgiveness for yourself and forgive others.
 **    May you share freely, never taking more than you give.
 */
package info.ata4.unity.extract;

import info.ata4.unity.DisUnitySettings;
import info.ata4.unity.asset.Asset;
import info.ata4.unity.asset.AssetFormat;
import info.ata4.unity.extract.handler.AudioClipHandler;
import info.ata4.unity.extract.handler.CubemapHandler;
import info.ata4.unity.extract.handler.ExtractHandler;
import info.ata4.unity.extract.handler.FontHandler;
import info.ata4.unity.extract.handler.MovieTextureHandler;
import info.ata4.unity.extract.handler.RawHandler;
import info.ata4.unity.extract.handler.ShaderHandler;
import info.ata4.unity.extract.handler.SubstanceArchiveHandler;
import info.ata4.unity.extract.handler.TextAssetHandler;
import info.ata4.unity.extract.handler.Texture2DHandler;
import info.ata4.unity.struct.AssetHeader;
import info.ata4.unity.struct.FieldTree;
import info.ata4.unity.struct.ObjectPath;
import info.ata4.unity.struct.ObjectTable;
import info.ata4.unity.util.ClassID;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.lang3.StringUtils;

/**
 * Extractor for asset files.
 * 
 * @author Nico Bergemann <barracuda415 at yahoo.de>
 */
public class AssetExtractor {
    
    private static final Logger L = Logger.getLogger(AssetExtractor.class.getName());
    
    private final Asset asset;
    private final DisUnitySettings settings;
    
    private Map<String, ExtractHandler> extractHandlerMap = new HashMap<>();
    private Set<ExtractHandler> extractHandlerSet = new HashSet<>();
    private RawHandler rawExtractHandler = new RawHandler();
    
    public AssetExtractor(Asset asset, DisUnitySettings settings) {
        this.asset = asset;
        this.settings = settings;
        
        addExtractHandler(new AudioClipHandler());
        addExtractHandler(new ShaderHandler());
        addExtractHandler(new SubstanceArchiveHandler());
        addExtractHandler(new Texture2DHandler());
        addExtractHandler(new CubemapHandler());
        addExtractHandler(new FontHandler());
        addExtractHandler(new TextAssetHandler());
        addExtractHandler(new MovieTextureHandler());
        extractHandlerSet.add(rawExtractHandler);
    }
    
    public final void addExtractHandler(ExtractHandler handler) {
        extractHandlerMap.put(handler.getClassName(), handler);
        extractHandlerSet.add(handler);
    }
    
    public final ExtractHandler getExtractHandler(String className) {
        return extractHandlerMap.get(className);
    }
    
    public final void clearExtractHandlers() {
        extractHandlerMap.clear();
        extractHandlerSet.clear();
        extractHandlerSet.add(rawExtractHandler);
    }

    public void extract(File dir, boolean raw) throws IOException {
        AssetHeader header = asset.getHeader();
        FieldTree fieldTree = asset.getFieldTree();
        ObjectTable objTable = asset.getObjectTable();
        ByteBuffer bb = asset.getDataBuffer();
        
        AssetFormat format = new AssetFormat(fieldTree.version, fieldTree.revision, header.format);
        
        for (ExtractHandler extractHandler : extractHandlerSet) {
            extractHandler.setExtractDir(dir);
            extractHandler.setAssetFormat(format);
        }
        
        for (ObjectPath path : objTable.getPaths()) {
            // skip filtered classes
            if (settings.isClassFiltered(path.classID2)) {
                continue;
            }
            
            String className = ClassID.getSafeNameForID(path.classID2);
                   
            // create a byte buffer for the data area
            bb.position(path.offset);
            ByteBuffer bbAsset = bb.slice();
            bbAsset.limit(path.length);
            bbAsset.order(ByteOrder.LITTLE_ENDIAN);

            ExtractHandler handler;

            if (raw) {
                rawExtractHandler.setClassName(className);
                handler = rawExtractHandler;
            } else {
                handler = getExtractHandler(className);
            }

            if (handler != null) {
                try {
                    handler.extract(bbAsset, path.pathID);
                } catch (Exception ex) {
                    L.log(Level.WARNING, "Can't extract asset " + className + " (" + path.pathID + ")", ex);
                }
            }
        }
    }

    public void split(File dir) throws IOException {
        ObjectTable objTable = asset.getObjectTable();
        FieldTree fieldTree = asset.getFieldTree();
        ByteBuffer bb = asset.getDataBuffer();
        
        // assets with just one object can't be split any further
        if (objTable.getPaths().size() == 1) {
            L.warning("Asset doesn't contain sub-assets!");
            return;
        }
        
        for (ObjectPath path : objTable.getPaths()) {
            // skip filtered classes
            if (settings.isClassFiltered(path.classID2)) {
                continue;
            }

            String className = ClassID.getSafeNameForID(path.classID2);
            
            Asset subAsset = new Asset();
            subAsset.getHeader().format = asset.getHeader().format;
            
            ObjectPath subFieldPath = new ObjectPath();
            subFieldPath.classID1 = path.classID1;
            subFieldPath.classID2 = path.classID2;
            subFieldPath.length = path.length;
            subFieldPath.offset = 0;
            subFieldPath.pathID = 1;
            subAsset.getObjectTable().getPaths().add(subFieldPath);
            
            FieldTree subFieldTree = subAsset.getFieldTree();
            subFieldTree.revision = fieldTree.revision;
            subFieldTree.version = -2;
            subFieldTree.setFormat(fieldTree.getFormat());
            subFieldTree.put(path.classID2, fieldTree.get(path.classID2));
            
            // create a byte buffer for the data area
            bb.position(path.offset);
            ByteBuffer bbAsset = bb.slice();
            bbAsset.limit(path.length);
            bbAsset.order(ByteOrder.LITTLE_ENDIAN);
            
            // probe asset name
            String subAssetName = getAssetName(bbAsset);
            bbAsset.rewind();
            
            if (subAssetName == null) {
                continue;
            }
            
            subAsset.setDataBuffer(bbAsset);
            
            File subAssetDir = new File(dir, className);
            if (!subAssetDir.exists()) {
                subAssetDir.mkdir();
            }
            
            File subAssetFile = new File(subAssetDir, subAssetName + ".asset");
            if (!subAssetFile.exists()) {
                L.log(Level.INFO, "Writing {0}", subAssetFile);
                subAsset.save(subAssetFile);
            }
        }
    }
    
    
    public static String getAssetName(ByteBuffer bb) {
        try {
            // make sure we have enough bytes to read a string at all
            if (bb.capacity() < 5) {
                L.log(Level.FINEST, "Not enough data for an asset name");
                return null;
            }
            
            int len = bb.getInt();
            if (len > 1024) {
                L.log(Level.FINEST, "Asset name too long: {0}", len);
                return null;
            }
            
            byte[] raw = new byte[len];
            bb.get(raw);
            
            String assetName = new String(raw).trim();
            
            // ignore bad strings
            if (assetName.isEmpty() || !StringUtils.isAsciiPrintable(assetName)) {
                L.log(Level.FINEST, "Invalid/empty asset name");
                return null;
            }
            
            return assetName;
        } catch (Exception ex) {
            return null;
        }
    }
}