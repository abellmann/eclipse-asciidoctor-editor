package de.jcup.asciidoctoreditor.preview;

import static de.jcup.asciidoctoreditor.util.EclipseUtil.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ICoreRunnable;
import org.eclipse.core.runtime.IProgressMonitor;

import de.jcup.asciidoctoreditor.AsciiDoctorEditor;
import de.jcup.asciidoctoreditor.AsciidoctorHTMLOutputParser;
import de.jcup.asciidoctoreditor.ContentTransformerData;
import de.jcup.asciidoctoreditor.EclipseDevelopmentSettings;
import de.jcup.asciidoctoreditor.TemporaryFileType;
import de.jcup.asciidoctoreditor.asciidoc.AsciiDocFileUtils;
import de.jcup.asciidoctoreditor.asciidoc.AsciiDocStringUtils;
import de.jcup.asciidoctoreditor.asciidoc.AsciiDoctorBackendType;
import de.jcup.asciidoctoreditor.asciidoc.AsciiDoctorWrapper;
import de.jcup.asciidoctoreditor.asciidoc.InstalledAsciidoctorException;
import de.jcup.asciidoctoreditor.asciidoc.Sha256StringEncoder;
import de.jcup.asciidoctoreditor.asciidoc.WrapperConvertData;
import de.jcup.asciidoctoreditor.asp.AspCompatibleProgressMonitorAdapter;
import de.jcup.asciidoctoreditor.preferences.AsciiDoctorEditorPreferences;
import de.jcup.asciidoctoreditor.script.AsciiDoctorErrorBuilder;
import de.jcup.asciidoctoreditor.script.AsciiDoctorMarker;
import de.jcup.asciidoctoreditor.util.AsciiDoctorEditorUtil;
import de.jcup.asciidoctoreditor.util.EclipseUtil;

/**
 * This is just the core part of building the HTML output for internal an
 * external previews. Here we have the temp asciidoc file creation, absolute
 * path conversion etc.
 * 
 * Between the asciidoc wrapper is called with the temporary asciidoc file.
 * 
 * 
 * @author albert
 *
 */
class AsciidocEditorPreviewBuildRunnnable implements ICoreRunnable {

    public static final Sha256StringEncoder STRING_ENCODER = new Sha256StringEncoder();

    BuildDoneListener buildDoneListener;
    AsciiDoctorBackendType backend;
    boolean internalPreview;
    AsciiDoctorEditor editor;
    private Worked worked;

    AsciidocEditorPreviewBuildRunnnable() {
        this.worked = new Worked();
    }

    @Override
    public void run(IProgressMonitor monitor) throws CoreException {
        try {

            /* before */
            before(monitor);

            /* work */
            buildHTMLPreviewFile(monitor);

            /* after */
            after(monitor);

        } finally {
            editor.getOutputBuildSemaphore().release();
        }
    }

    private class ConvertData {
        String originPath;
        File flatFile;
        
        @Override
        public String toString() {
            return "ConverterData:origin="+originPath+", to "+flatFile;
        }
    }

    private void buildHTMLPreviewFile(IProgressMonitor monitor) {
        if (isCanceled(monitor)) {
            return;
        }
        AsciiDoctorWrapper asciidocWrapper = editor.getWrapper();
        File outputFile = null;

        try {
            safeAsyncExec(() -> AsciiDoctorEditorUtil.removeScriptErrors(editor));

            monitor.subTask("RESOLVE");

            File editorFileOrNull = editor.getEditorFileOrNull();

            /* -------------------------- */
            /* --- Transform Asciidoc --- */
            /* --- content if necessary - */
            /* --- (e.g. plantuml files)- */
            /* ----before HTML generation */
            /* -------------------------- */
            File tempAsciiDocFileToConvertIntoHTML = createTransformedTempFileOrNull(editorFileOrNull);
            if (tempAsciiDocFileToConvertIntoHTML == null) {
                return;
            }

            if (isCanceled(monitor)) {
                return;
            }
            increaseWorked(monitor);

            /* content exists as simple file */
            monitor.subTask("GENERATE");

            /* -------------------------- */
            /* -------------------------- */
            /* ----Call Asciidoctor ----- */
            /* -------------------------- */
            /* -------------------------- */
            convertTempAsciidocFileToHTML(monitor, asciidocWrapper, editorFileOrNull, tempAsciiDocFileToConvertIntoHTML);

            increaseWorked(monitor);

            if (isCanceled(monitor)) {
                return;
            }

            monitor.subTask("READ AND TRANSFORM");

            /* -------------------------- */
            /* ----- Read origin------- */
            /* ----- Asciidoc output----- */
            /* -------------------------- */
            File fileToRender = asciidocWrapper.getContext().getFileToRender();
            String originAsciiDocHtml = readFileCreatedByAsciiDoctor(fileToRender, editor.getEditorId());

            increaseWorked(monitor);

            /* -------------------------- */
            /* -- Read image pathes -- */
            /* ---from Asciidoc output -- */
            /* -------------------------- */
            List<ConvertData> list = new ArrayList<>();

            AsciidoctorHTMLOutputParser parser = new AsciidoctorHTMLOutputParser();
            File tempFolder = asciidocWrapper.getTempFolder().toFile();
            File imageOutDir = new File(tempFolder,"img");
            Set<String> pathes = parser.findImageSourcePathes(originAsciiDocHtml);
            Set<String> pathes2 = new LinkedHashSet<String>();
            for (String path : pathes) {
                File file = new File(path);
                if (file.exists()) {
                      /*keepas is */
                    continue;
                }
                else {
                    String p2 = file.getPath();
                    String replacePath = null;
                    if(p2.startsWith(".")) {
                        // relative path
                        file = new File(asciidocWrapper.getContext().getBaseDir(),p2);
                        if(file.exists()) {
                            replacePath=file.getCanonicalPath();
                        }
                    }
                    if (replacePath==null) {
                        /* lets assume this generated into imageoutdir...*/
                        replacePath = new File(imageOutDir,file.getName()).getCanonicalPath();
                    }
                        
                    
                    originAsciiDocHtml=originAsciiDocHtml.replace(path, replacePath);
                    pathes2.add(replacePath);
                    
                    
//                    File flatFile = new File(tempFolder, AsciiDocFileUtils.createFlatFileName(file, STRING_ENCODER));
//                    ConvertData converterData = new ConvertData();
//                    converterData.flatFile = flatFile;
//                    converterData.originPath = path;
//                    list.add(converterData);
//                    
//                    System.out.println(converterData);
                }
            }
            
            

            /* -------------------------- */
            /* -- Drop absolute Pathes -- */
            /* ---from Asciidoc output -- */
            /* -------------------------- */
            // /* @formatter:off */
            // transforms
            // <img src="/tmp/asciidoctor-editor-temp/project_all-testscripts-project-1310962511/images/asciidoctor-editor-logo.png" alt="asciidoctor editor logo">
            // to
            // <img src="./images/asciidoctor-editor-logo.png" alt="asciidoctor editor logo">
            /* @formatter:on */
            String asciidocHTMLWithoutAbsolutePathes = originAsciiDocHtml;//transformAbsolutePathesToRelatives(originAsciiDocHtml);

            if (isCanceled(monitor)) {
                return;
            }
            increaseWorked(monitor);

//            /* -------------------------- */
//            /* -- Inspect images and -- */
//            /* ---ensure available in -- */
//            /* ---output folder-- */
//            /* -------------------------- */
//            AsciidoctorHTMLOutputParser parser = new AsciidoctorHTMLOutputParser();
//            Set<String> relativeImgSrcPathes = parser.findImageSourcePathes(asciidocHTMLWithoutAbsolutePathes);
//            AsciiDoctorImageProvider imageProvider = asciidocWrapper.getContext().getImageProvider();
//
//            for (String relativeImgSrcPath : relativeImgSrcPathes) {
//                imageProvider.ensureImageByRelativePath(relativeImgSrcPath);
//            }

            /* -------------------------- */
            /* -- Create final preview -- */
            /* ---HTML file ------------- */
            /* -------------------------- */
            outputFile = enrichPreviewHTMLAndWriteToDisk(monitor, asciidocWrapper, asciidocHTMLWithoutAbsolutePathes);

        } catch (Throwable e) {
            /*
             * Normally I would do a catch(Exception e), but we must use catch(Throwable t)
             * here. Reason (at least eclipse neon) we got full eclipse editor tab freeze
             * problem when a jruby class not found error occurs!
             */
            writeFallbackWhenPreviewFileWasNotWritten(e);

        }
        if (EclipseDevelopmentSettings.DEBUG_LOGGING_ENABLED) {
            System.out.println("worked:" + worked);
            System.out.println("outputFile:" + outputFile);
        }

    }

    private void increaseWorked(IProgressMonitor monitor) {
        monitor.worked(++worked.amount);
    }

    private void convertTempAsciidocFileToHTML(IProgressMonitor monitor, AsciiDoctorWrapper asciidocWrapper, File editorFileOrNull, File tempFileToConvertIntoHTML) throws Exception {
        WrapperConvertData wrapperConvertData = createWrapperData(editorFileOrNull, tempFileToConvertIntoHTML);
        editor.beforeAsciidocConvert(wrapperConvertData);

        /* convert */
        asciidocWrapper.convert(wrapperConvertData, backend, new AspCompatibleProgressMonitorAdapter(monitor));
    }

    private WrapperConvertData createWrapperData(File editorFileOrNull, File fileToConvertIntoHTML) {
        WrapperConvertData data = new WrapperConvertData();
        data.targetType = editor.getType();
        data.asciiDocFile = fileToConvertIntoHTML;
        data.editorId = editor.getEditorId();
        data.useHiddenFile = isNeedingAHiddenEditorFile(editorFileOrNull, fileToConvertIntoHTML);
        data.editorFileOrNull = editorFileOrNull;
        data.internalPreview = internalPreview;
        return data;
    }

    /*
     * transform if necessary - e.g. plantuml files must be converted before to
     * adoc...
     */
    private File createTransformedTempFileOrNull(File editorFileOrNull) throws IOException {
        File fileToConvertIntoHTML = null;

        if (editorFileOrNull == null) {
            String asciiDoc = editor.getDocumentText();
            fileToConvertIntoHTML = createTransformedTempFileFromTextContent(asciiDoc);
        } else {
            fileToConvertIntoHTML = createTransformedTempFileFromEditorFile(editorFileOrNull);
        }
        return fileToConvertIntoHTML;
    }

    private void writeFallbackWhenPreviewFileWasNotWritten(Throwable e) {
        /*
         * This means the ASCIIDOCTOR wrapper was not able to convert - so we have to
         * clean the former output and show up a marker for complete file
         */
        StringBuilder htmlSb = new StringBuilder();
        htmlSb.append("<h4");
        if (editor.isAsciiDoctorError(e)) {
            htmlSb.append("Asciidoctor error");
        } else {
            htmlSb.append("Unknown error");
        }
        htmlSb.append("</h4");

        safeAsyncExec(() -> {

            String errorMessage = editor.fetchAsciidoctorErrorMessage(e);

            AsciiDoctorErrorBuilder builder = new AsciiDoctorErrorBuilder();
            AsciiDoctorMarker error = builder.build(errorMessage);

            editor.getBrowserAccess().safeBrowserSetText(htmlSb.toString());
            AsciiDoctorEditorUtil.addAsciiDoctorMarker(editor, -1, error, IMarker.SEVERITY_ERROR);

            if (isLoggingNecessary(e)) {
                AsciiDoctorEditorUtil.logError("AsciiDoctor error occured:" + e.getMessage(), e);
            }
        });
    }

    private File enrichPreviewHTMLAndWriteToDisk(IProgressMonitor monitor, AsciiDoctorWrapper asciidocWrapper, String asciiDocHtml) {
        String previewHTML;
        if (internalPreview) {
            previewHTML = asciidocWrapper.enrichHTML(asciiDocHtml, 0);
        } else {
            int refreshAutomaticallyInSeconds = AsciiDoctorEditorPreferences.getInstance().getAutoRefreshInSecondsForExternalBrowser();
            previewHTML = asciidocWrapper.enrichHTML(asciiDocHtml, refreshAutomaticallyInSeconds);
        }

        return writePreviewHTMLFile(monitor, previewHTML);
    }

    private File writePreviewHTMLFile(IProgressMonitor monitor, String previewHTML) {
        File outputFile = null;
        try {
            if (internalPreview) {
                monitor.subTask("WRITE INTERNAL PREVIEW");
                outputFile = editor.getTemporaryInternalPreviewFile();
            } else {
                monitor.subTask("WRITE EXTERNAL PREVIEW");
                outputFile = editor.getTemporaryExternalPreviewFile();
            }
            AsciiDocStringUtils.writeTextToUTF8File(previewHTML, outputFile);
            increaseWorked(monitor);

        } catch (IOException e1) {
            AsciiDoctorEditorUtil.logError("Was not able to save temporary files for preview!", e1);
        }
        return outputFile;
    }

    private File createTransformedTempFileFromTextContent(String asciiDoc) throws IOException {
        File fileToConvertIntoHTML;
        String text;
        if (editor.getContentTransformer().isTransforming(asciiDoc)) {

            ContentTransformerData data = new ContentTransformerData();
            data.origin = asciiDoc;
            text = editor.getContentTransformer().transform(data);
        } else {
            text = asciiDoc;
        }
        fileToConvertIntoHTML = createTransformedTempfile("no_origin_file_defined", text);
        return fileToConvertIntoHTML;
    }

    private File createTransformedTempFileFromEditorFile(File editorFile) throws IOException {
        if (editorFile == null || !editorFile.exists()) {
            return null;
        }

        String originText = AsciiDocStringUtils.readUTF8FileToString(editorFile);
        if (originText == null) {
            return null;
        }
        if (!editor.getContentTransformer().isTransforming(originText)) {
            return editorFile;
        }
        return createTransformedTempfile(editorFile.getName(), originText);
    }

    private File createTransformedTempfile(String filename, String text) throws IOException {
        Path tempFolder = editor.getWrapper().getTempFolder();
        File newTempFile = AsciiDocFileUtils.createTempFileForConvertedContent(tempFolder, editor.getEditorId(), filename);

        ContentTransformerData data = new ContentTransformerData();
        data.origin = text;
        data.filename = filename;

        String transformed = editor.getContentTransformer().transform(data);
        try {
            return AsciiDocStringUtils.writeTextToUTF8File(transformed, newTempFile);
        } catch (IOException e) {
            logError("Was not able to write transformed file:" + filename, e);
            return null;
        }
    }

    /*
     * Transforms given HTML and removes all pathes being absolute from HTML
     */
    private String transformAbsolutePathesToRelatives(String html) {

        AbsolutePathPatternFactory patternFactory = new AbsolutePathPatternFactory();
        AsciiDoctorWrapper wrapper = editor.getWrapper();
        Pattern tempFolderPattern = patternFactory.createRemoveAbsolutePathToTempFolderPattern(wrapper);
        Pattern baseFolderPattern = patternFactory.createRemoveAbsolutePathToBaseFolderPattern(wrapper);

        String htmlWithoutAbsolutePathes = tempFolderPattern.matcher(html).replaceAll("");
        htmlWithoutAbsolutePathes = baseFolderPattern.matcher(htmlWithoutAbsolutePathes).replaceAll("");
        return htmlWithoutAbsolutePathes;
    }

    private String readFileCreatedByAsciiDoctor(File fileToConvertIntoHTML, long editorId) {
        File generatedFile = editor.getWrapper().getTempFileFor(fileToConvertIntoHTML, editorId, TemporaryFileType.ORIGIN);
        try {
            return AsciiDocStringUtils.readUTF8FileToString(generatedFile);
        } catch (IOException e) {
            AsciiDoctorEditorUtil.logError("Was not able to build new full html variant", e);
            return "";
        }
    }

    /* -------------------------------------------- */
    /* ----------------Helpers--------------------- */
    /* -------------------------------------------- */
    private String getSafeFileName() {
        if (editor.getTemporaryInternalPreviewFile() == null) {
            return "<unknown>";
        }
        return editor.getTemporaryInternalPreviewFile().getName();
    }

    /**
     * Asciidoctor starts normally from a root document and resolves pathes etc. on
     * the fly by using the base directory. So far so good. but when resolving base
     * directory for e.g. images, diagrams etc. and setting it but rendering a sub
     * file this does always break the includes, because either images do not longer
     * work or the include.<br>
     * <br>
     * To prevent this we do following trick. We always create a temporary hidden
     * file which will include the corresponding real editor file This temporary
     * file is always settled at base folder
     */
    private boolean isNeedingAHiddenEditorFile(File editorFileOrNull, File fileToConvertIntoHTML) {
        /*
         * Still same file so not converted, means still same .adoc file for those files
         * we do always create a temporary editor file which does include the origin one
         * - reason see description in javadoc above
         */
        // one exception: when we are rendering plantuml or dita files we do not use the
        // hidden editor file (because there
        // is already a custom .adoc file...
        return fileToConvertIntoHTML.equals(editorFileOrNull);
    }

    private boolean isLoggingNecessary(Throwable e) {
        if (e == null || e instanceof InstalledAsciidoctorException) {
            /* InstalledAsciidoctorException is already logged */
            return false;
        }
        return true;
    }

    private void before(IProgressMonitor monitor) {
        monitor.beginTask("Building document " + getSafeFileName(), 7);
    }

    private void after(IProgressMonitor monitor) {
        if (editor.isInternalPreview()) {
            monitor.subTask("show internal");
            editor.ensureInternalBrowserShowsURL(monitor);
        }
        monitor.worked(7);

        handleBuildDone(buildDoneListener);

        monitor.done();
    }

    private void ensureFocused() {
        if (editor.isInternalPreview()) {
            /*
             * do a "refocus" on safe - sometimes necessary on windows. Seems browser grabs
             * sometimes focus...
             */
            EclipseUtil.safeAsyncExec(() -> editor.refocus());
        }
    }

    private void handleBuildDone(BuildDoneListener buildDoneListener) {
        ensureFocused();

        if (buildDoneListener != null) {
            buildDoneListener.buildDone();
        }
    }

    public boolean isCanceled(IProgressMonitor monitor) {
        if (monitor == null) {
            return false; // no chance to cancel...
        }
        return monitor.isCanceled();
    }

}