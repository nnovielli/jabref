package org.jabref.logic.importer.fileformat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Scanner;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.jabref.logic.importer.ImportFormatPreferences;
import org.jabref.logic.importer.Importer;
import org.jabref.logic.importer.ParserResult;
import org.jabref.logic.l10n.Localization;
import org.jabref.logic.util.OS;
import org.jabref.logic.util.StandardFileType;
import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.field.Field;
import org.jabref.model.entry.field.FieldFactory;
import org.jabref.model.entry.field.StandardField;
import org.jabref.model.entry.field.UnknownField;
import org.jabref.model.entry.types.EntryType;
import org.jabref.model.entry.types.StandardEntryType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This importer parses text format citations using the online API of FreeCite -
 * Open Source Citation Parser http://freecite.library.brown.edu/
 */
public class FreeCiteImporter extends Importer {

    private static final Logger LOGGER = LoggerFactory.getLogger(FreeCiteImporter.class);

    private final ImportFormatPreferences importFormatPreferences;


    public FreeCiteImporter(ImportFormatPreferences importFormatPreferences) {
        this.importFormatPreferences = importFormatPreferences;
    }

    @Override
    public boolean isRecognizedFormat(BufferedReader reader) throws IOException {
        Objects.requireNonNull(reader);
        // TODO: We don't know how to recognize text files, therefore we return "false"
        return false;
    }

    @Override
    public ParserResult importDatabase(BufferedReader reader) throws IOException {
        try (Scanner scan = new Scanner(reader)) {
            String text = scan.useDelimiter("\\A").next();
            return importEntries(text);
        }
    }

    public ParserResult importEntries(String text) {
        // URLencode the string for transmission
        String urlencodedCitation = null;
        try {
            urlencodedCitation = URLEncoder.encode(text, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            LOGGER.warn("Unsupported encoding", e);
        }

        // Send the request
        URL url;
        URLConnection conn;
        try {
            url = new URL("http://freecite.library.brown.edu/citations/create");
            conn = url.openConnection();
        } catch (MalformedURLException e) {
            LOGGER.warn("Bad URL", e);
            return new ParserResult();
        } catch (IOException e) {
            LOGGER.warn("Could not download", e);
            return new ParserResult();
        }
        try {
            conn.setRequestProperty("accept", "text/xml");
            conn.setDoOutput(true);
            OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream());

            String data = "citation=" + urlencodedCitation;
            // write parameters
            writer.write(data);
            writer.flush();
        } catch (IllegalStateException e) {
            LOGGER.warn("Already connected.", e);
        } catch (IOException e) {
            LOGGER.warn("Unable to connect to FreeCite online service.", e);
            return ParserResult.fromErrorMessage(Localization.lang("Unable to connect to FreeCite online service."));
        }
        // output is in conn.getInputStream();
        // new InputStreamReader(conn.getInputStream())
        List<BibEntry> res = new ArrayList<>();

        XMLInputFactory factory = XMLInputFactory.newInstance();
        try {
            XMLStreamReader parser = factory.createXMLStreamReader(conn.getInputStream());
            while (parser.hasNext()) {
                if ((parser.getEventType() == XMLStreamConstants.START_ELEMENT)
                        && "citation".equals(parser.getLocalName())) {
                    parser.nextTag();

                    StringBuilder noteSB = new StringBuilder();

                    BibEntry e = new BibEntry();
                    // fallback type
                    EntryType type = StandardEntryType.InProceedings;

                    while (!((parser.getEventType() == XMLStreamConstants.END_ELEMENT)
                            && "citation".equals(parser.getLocalName()))) {
                        if (parser.getEventType() == XMLStreamConstants.START_ELEMENT) {
                            Field field = FieldFactory.parseField(parser.getLocalName());
                            if (new UnknownField("authors").equals(field)) {
                                StringBuilder sb = new StringBuilder();
                                parser.nextTag();

                                while (parser.getEventType() == XMLStreamConstants.START_ELEMENT) {
                                    // author is directly nested below authors
                                    assert "author".equals(parser.getLocalName());

                                    String author = parser.getElementText();
                                    if (sb.length() == 0) {
                                        // first author
                                        sb.append(author);
                                    } else {
                                        sb.append(" and ");
                                        sb.append(author);
                                    }
                                    assert parser.getEventType() == XMLStreamConstants.END_ELEMENT;
                                    assert "author".equals(parser.getLocalName());
                                    parser.nextTag();
                                    // current tag is either begin:author or
                                    // end:authors
                                }
                                e.setField(StandardField.AUTHOR, sb.toString());
                            } else if (StandardField.JOURNAL.equals(field)) {
                                // we guess that the entry is a journal
                                // the alternative way is to parse
                                // ctx:context-objects / ctx:context-object / ctx:referent / ctx:metadata-by-val / ctx:metadata / journal / rft:genre
                                // the drawback is that ctx:context-objects is NOT nested in citation, but a separate element
                                // we would have to change the whole parser to parse that format.
                                type = StandardEntryType.Article;
                                e.setField(field, parser.getElementText());
                            } else if (new UnknownField("tech").equals(field)) {
                                type = StandardEntryType.TechReport;
                                // the content of the "tech" field seems to contain the number of the technical report
                                e.setField(StandardField.NUMBER, parser.getElementText());
                            } else if (StandardField.DOI.equals(field) || StandardField.INSTITUTION.equals(field)
                                    || StandardField.LOCATION.equals(field) || StandardField.NUMBER.equals(field)
                                    || StandardField.NOTE.equals(field) || StandardField.TITLE.equals(field)
                                    || StandardField.PAGES.equals(field) || StandardField.PUBLISHER.equals(field)
                                    || StandardField.VOLUME.equals(field) || StandardField.YEAR.equals(field)) {
                                e.setField(field, parser.getElementText());
                            } else if (StandardField.BOOKTITLE.equals(field)) {
                                String booktitle = parser.getElementText();
                                if (booktitle.startsWith("In ")) {
                                    // special treatment for parsing of
                                    // "In proceedings of..." references
                                    booktitle = booktitle.substring(3);
                                }
                                e.setField(StandardField.BOOKTITLE, booktitle);
                            } else if (new UnknownField("raw_string").equals(field)) {
                                // raw input string is ignored
                            } else {
                                // all other tags are stored as note
                                noteSB.append(field);
                                noteSB.append(':');
                                noteSB.append(parser.getElementText());
                                noteSB.append(OS.NEWLINE);
                            }
                        }
                        parser.next();
                    }

                    if (noteSB.length() > 0) {
                        String note;
                        if (e.hasField(StandardField.NOTE)) {
                            // "note" could have been set during the parsing as FreeCite also returns "note"
                            note = e.getField(StandardField.NOTE).get().concat(OS.NEWLINE)
                                    .concat(noteSB.toString());
                        } else {
                            note = noteSB.toString();
                        }
                        e.setField(StandardField.NOTE, note);
                    }

                    // type has been derived from "genre"
                    // has to be done before label generation as label generation is dependent on entry type
                    e.setType(type);

                    res.add(e);
                }
                parser.next();
            }
            parser.close();
        } catch (IOException | XMLStreamException ex) {
            LOGGER.warn("Could not parse", ex);
            return new ParserResult();
        }

        return new ParserResult(res);
    }

    @Override
    public String getName() {
        return "text citations";
    }

    @Override
    public StandardFileType getFileType() {
        return StandardFileType.FREECITE;
    }

    @Override
    public String getDescription() {
        return "This importer parses text format citations using the online API of FreeCite.";
    }

}
