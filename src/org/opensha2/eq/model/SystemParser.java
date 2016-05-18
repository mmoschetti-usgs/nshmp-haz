package org.opensha2.eq.model;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import static org.opensha2.eq.model.SourceAttribute.DEPTH;
import static org.opensha2.eq.model.SourceAttribute.DIP;
import static org.opensha2.eq.model.SourceAttribute.ID;
import static org.opensha2.eq.model.SourceAttribute.INDICES;
import static org.opensha2.eq.model.SourceAttribute.NAME;
import static org.opensha2.eq.model.SourceAttribute.RAKE;
import static org.opensha2.eq.model.SourceAttribute.TYPE;
import static org.opensha2.eq.model.SourceAttribute.WEIGHT;
import static org.opensha2.eq.model.SourceAttribute.WIDTH;
import static org.opensha2.mfd.MfdType.GR;
import static org.opensha2.mfd.MfdType.GR_TAPER;
import static org.opensha2.mfd.MfdType.INCR;
import static org.opensha2.mfd.MfdType.SINGLE;
import static org.opensha2.util.Parsing.rangeStringToIntList;
import static org.opensha2.util.Parsing.readDouble;
import static org.opensha2.util.Parsing.readEnum;
import static org.opensha2.util.Parsing.readInt;
import static org.opensha2.util.Parsing.readString;

import org.opensha2.eq.fault.surface.GriddedSurface;
import org.opensha2.eq.model.MfdHelper.SingleData;
import org.opensha2.mfd.IncrementalMfd;
import org.opensha2.mfd.MfdType;
import org.opensha2.mfd.Mfds;

import org.xml.sax.Attributes;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import com.google.common.collect.Iterables;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.logging.Logger;

import javax.xml.parsers.SAXParser;

/*
 * Non-validating indexed fault source parser. SAX parser 'Attributes' are
 * stateful and cannot be stored. This class is not thread safe.
 * 
 * @author Peter Powers
 */
@SuppressWarnings("incomplete-switch")
class SystemParser extends DefaultHandler {

  static final String GRIDSOURCE_FILENAME = "grid_sources.xml";
  static final String RUPTURES_FILENAME = "fault_ruptures.xml";
  static final String SECTIONS_FILENAME = "fault_sections.xml";

  private final Logger log = Logger.getLogger(SystemParser.class.getName());
  private final SAXParser sax;
  private boolean used = false;

  private Locator locator;

  private GmmSet gmmSet;
  // TODO can these be RuptureSurfaces??
  private List<GriddedSurface> sections;
  private SystemSourceSet sourceSet;
  private SystemSourceSet.Builder sourceSetBuilder;

  /*
   * Currently, SystemSourceSets simply maintain lists of magnitudes and rates
   * for each rupture, but they may be refactored in the future to support one
   * or more MFDs (e.g. aleatory uncertainty on magnitude may be desired). This
   * parser has been constructed assuming this will be the case and although it
   * adds some unnecessary overhead at this time (mag and rate are simply pulled
   * from the MFD for the SystemSourceSet.Builder; the MFD reference is not
   * retained), it ensures consistency with other SourceSet implementations and
   * that MFDs are fully specified between sources and defaults.
   */
  private IncrementalMfd mfd;

  // Default MFD data
  private boolean parsingDefaultMFDs = false;
  private MfdHelper.Builder mfdHelperBuilder;
  private MfdHelper mfdHelper;

  // Traces are the only text content in source files
  private boolean readingTrace = false;
  private StringBuilder traceBuilder = null;

  private SystemParser(SAXParser sax) {
    this.sax = sax;
  }

  static SystemParser create(SAXParser sax) {
    return new SystemParser(checkNotNull(sax));
  }

  SystemSourceSet parse(InputStream sectionsIn, InputStream rupturesIn, GmmSet gmmSet)
      throws SAXException, IOException {
    checkState(!used, "This parser has expired");
    this.gmmSet = gmmSet;
    sections = parseSections(sectionsIn);
    sax.parse(rupturesIn, this);
    checkState(sourceSet.size() > 0, "SystemSourceSet is empty");
    used = true;
    return sourceSet;
  }

  private List<GriddedSurface> parseSections(InputStream in) throws SAXException, IOException {
    SystemSectionParser parser = SystemSectionParser.create(sax);
    return parser.parse(in);
  }

  @Override
  public void startElement(String uri, String localName, String qName, Attributes atts)
      throws SAXException {

    SourceElement e = null;
    try {
      e = SourceElement.fromString(qName);
    } catch (IllegalArgumentException iae) {
      throw new SAXParseException("Invalid element <" + qName + ">", locator, iae);
    }

    try {
      switch (e) {

        case SYSTEM_SOURCE_SET:
          String name = readString(NAME, atts);
          int id = readInt(ID, atts);
          double weight = readDouble(WEIGHT, atts);
          sourceSetBuilder = new SystemSourceSet.Builder();
          sourceSetBuilder
            .name(name)
            .id(id)
            .weight(weight)
            .gmms(gmmSet);
          sourceSetBuilder.sections(sections);
          log.info("   Sections: " + sections.size());
          log.info("Rupture set: " + name + "/" + RUPTURES_FILENAME);
          log.info(" Set weight: " + weight);
          mfdHelperBuilder = MfdHelper.builder();
          mfdHelper = mfdHelperBuilder.build(); // dummy; usually
          // overwritten
          break;

        case DEFAULT_MFDS:
          parsingDefaultMFDs = true;
          break;

        case INCREMENTAL_MFD:
          if (parsingDefaultMFDs) {
            mfdHelperBuilder.addDefault(atts);
            break;
          }
          mfd = buildMfd(atts);
          break;

        case GEOMETRY:
          sourceSetBuilder
            .mag(mfd.getX(0))
            .rate(mfd.getY(0))
            .indices(rangeStringToIntList(readString(INDICES, atts)))
            .depth(readDouble(DEPTH, atts))
            .dip(readDouble(DIP, atts))
            .rake(readDouble(RAKE, atts))
            .width(readDouble(WIDTH, atts));
          break;

      }

    } catch (Exception ex) {
      throw new SAXParseException("Error parsing <" + qName + ">", locator, ex);
    }
  }

  @Override
  public void endElement(String uri, String localName, String qName)
      throws SAXException {

    SourceElement e = null;
    try {
      e = SourceElement.fromString(qName);
    } catch (IllegalArgumentException iae) {
      throw new SAXParseException("Invalid element <" + qName + ">", locator, iae);
    }

    try {
      switch (e) {

        case DEFAULT_MFDS:
          parsingDefaultMFDs = false;
          mfdHelper = mfdHelperBuilder.build();
          checkDefaultMfds();
          break;

        case SYSTEM_SOURCE_SET:
          sourceSet = sourceSetBuilder.build();
          log.info("   Ruptures: " + sourceSet.size());
          break;
      }

    } catch (Exception ex) {
      throw new SAXParseException("Error parsing <" + qName + ">", locator, ex);
    }
  }

  @Override
  public void characters(char ch[], int start, int length) throws SAXException {
    if (readingTrace) traceBuilder.append(ch, start, length);
  }

  @Override
  public void setDocumentLocator(Locator locator) {
    this.locator = locator;
  }

  private void checkDefaultMfds() {
    checkState(
      mfdHelper.typeCount(SINGLE) <= 1 &&
        mfdHelper.typeCount(INCR) == 0 &&
        mfdHelper.typeCount(GR) == 0 &&
        mfdHelper.typeCount(GR_TAPER) == 0,
      "Only one SINGLE default MFD may be defined");
  }

  private IncrementalMfd buildMfd(Attributes atts) {
    MfdType type = readEnum(TYPE, atts, MfdType.class);
    checkState(type == MfdType.SINGLE, "Only SINGLE mfds are supported");
    // ensures only one SINGLE mfd exists
    SingleData singleData = Iterables.getOnlyElement(mfdHelper.singleData(atts));
    return Mfds.newSingleMFD(singleData.m, singleData.rate * singleData.weight,
      singleData.floats);
  }

}
