/*
 * Copyright (c) 1998-2018 University Corporation for Atmospheric Research/Unidata
 * See LICENSE for license information.
 */


package ucar.nc2.iosp.mcidas;


import com.google.common.base.MoreObjects;
import ucar.mcidas.AREAnav;
import ucar.mcidas.AreaDirectory;
import ucar.mcidas.AreaFile;
import ucar.mcidas.AreaFileException;
import ucar.mcidas.Calibrator;
import ucar.mcidas.CalibratorException;
import ucar.mcidas.CalibratorFactory;
import ucar.mcidas.McIDASException;
import ucar.mcidas.McIDASUtil;
import ucar.ma2.Array;
import ucar.ma2.ArrayChar;
import ucar.ma2.ArrayInt;
import ucar.ma2.DataType;
import ucar.ma2.Index;
import ucar.ma2.InvalidRangeException;
import ucar.ma2.Range;
import ucar.ma2.Section;
import ucar.nc2.Attribute;
import ucar.nc2.Dimension;
import ucar.nc2.NetcdfFile;
import ucar.nc2.Variable;
import ucar.nc2.constants.CDM;
import ucar.nc2.constants.CF;
import ucar.nc2.constants.FeatureType;
import ucar.nc2.units.DateFormatter;
import ucar.unidata.geoloc.ProjectionImpl;
import ucar.unidata.io.RandomAccessFile;
import ucar.unidata.util.Parameter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;


/**
 * Class to read an AREA file and create a netCDF data structure
 * from it.
 *
 * @author Don Murray
 */
public class AreaReader {


  /**
   * The AREA file
   */
  AreaFile af;

  /**
   * The AREA navigation
   */
  private AREAnav nav;

  /**
   * The raw AREA directory
   */
  private int[] dirBlock;

  /**
   * The raw nav block
   */
  private int[] navBlock;

  /**
   * The AREA directory
   */
  private AreaDirectory ad;

  /**
   * The calibrator
   */
  Calibrator calibrator;

  /**
   * list of bands
   */
  int[] bandMap;

  /**
   * calibration scale
   */
  private float calScale = 1.f;

  /**
   * calibration scale
   */
  private String calUnit;


  /**
   * initialize; note that the file is reopened here
   *
   * @param location the AREA file to open
   * @param ncfile the netCDF file to fill out
   * @return true if successful
   * @throws AreaFileException problem opening the area file
   */
  public boolean init(String location, NetcdfFile ncfile) throws AreaFileException {

    af = new AreaFile(location);

    // read metadata
    dirBlock = af.getDir();
    ad = af.getAreaDirectory();
    int numElements = ad.getElements();
    int numLines = ad.getLines();
    int numBands = ad.getNumberOfBands();
    bandMap = ad.getBands();
    navBlock = af.getNav();
    Date nomTime = ad.getNominalTime();
    DateFormatter df = new DateFormatter();
    try {
      nav = AREAnav.makeAreaNav(navBlock, af.getAux());
    } catch (McIDASException me) {
      throw new AreaFileException(me.getMessage());
    }
    int sensor = dirBlock[AreaFile.AD_SENSORID];
    String calName = McIDASUtil.intBitsToString(dirBlock[AreaFile.AD_CALTYPE]);
    int calType = getCalType(calName);

    // TODO: Need to support calibrated data.
    if ((af.getCal() != null) && CalibratorFactory.hasCalibrator(sensor)) {
      try {
        calibrator = CalibratorFactory.getCalibrator(sensor, calType, af.getCal());
      } catch (CalibratorException ce) {
        calibrator = null;
      }
    }
    calUnit = ad.getCalibrationUnitName();
    calScale = (1.0f / ad.getCalibrationScaleFactor());

    // make the dimensions
    Dimension elements = new Dimension("elements", numElements);
    Dimension lines = new Dimension("lines", numLines);
    Dimension bands = new Dimension("bands", numBands);
    Dimension time = new Dimension("time", 1);
    Dimension dirDim = new Dimension("dirSize", AreaFile.AD_DIRSIZE);
    Dimension navDim = new Dimension("navSize", navBlock.length);
    List<Dimension> image = new ArrayList<>();
    image.add(time);
    image.add(bands);
    image.add(lines);
    image.add(elements);
    ncfile.addDimension(null, elements);
    ncfile.addDimension(null, lines);
    ncfile.addDimension(null, bands);
    ncfile.addDimension(null, time);
    ncfile.addDimension(null, dirDim);
    ncfile.addDimension(null, navDim);


    Array varArray;

    // make the variables

    // time
    Variable timeVar = new Variable(ncfile, null, null, "time");
    timeVar.setDataType(DataType.INT);
    timeVar.setDimensions("time");
    timeVar.addAttribute(new Attribute(CDM.UNITS, "seconds since " + df.toDateTimeString(nomTime)));
    timeVar.addAttribute(new Attribute("long_name", "time"));
    varArray = new ArrayInt.D1(1, false);
    ((ArrayInt.D1) varArray).set(0, 0);
    timeVar.setCachedData(varArray, false);
    ncfile.addVariable(null, timeVar);


    // lines and elements
    Variable lineVar = new Variable(ncfile, null, null, "lines");
    lineVar.setDataType(DataType.INT);
    lineVar.setDimensions("lines");
    // lineVar.addAttribute(new Attribute(CDM.UNITS, "km"));
    lineVar.addAttribute(new Attribute("standard_name", "projection_y_coordinate"));
    varArray = new ArrayInt.D1(numLines, false);
    for (int i = 0; i < numLines; i++) {
      int pos = nav.isFlippedLineCoordinates() ? i : numLines - i - 1;
      ((ArrayInt.D1) varArray).set(i, pos);
    }
    lineVar.setCachedData(varArray, false);
    ncfile.addVariable(null, lineVar);

    Variable elementVar = new Variable(ncfile, null, null, "elements");
    elementVar.setDataType(DataType.INT);
    elementVar.setDimensions("elements");
    // elementVar.addAttribute(new Attribute(CDM.UNITS, "km"));
    elementVar.addAttribute(new Attribute("standard_name", "projection_x_coordinate"));
    varArray = new ArrayInt.D1(numElements, false);
    for (int i = 0; i < numElements; i++) {
      ((ArrayInt.D1) varArray).set(i, i);
    }
    elementVar.setCachedData(varArray, false);
    ncfile.addVariable(null, elementVar);


    // TODO: handle bands and calibrations
    Variable bandVar = new Variable(ncfile, null, null, "bands");
    bandVar.setDataType(DataType.INT);
    bandVar.setDimensions("bands");
    bandVar.addAttribute(new Attribute("long_name", "spectral band number"));
    bandVar.addAttribute(new Attribute("axis", "Z"));
    Array bandArray = new ArrayInt.D1(numBands, false);
    for (int i = 0; i < numBands; i++) {
      ((ArrayInt.D1) bandArray).set(i, bandMap[i]);
    }
    bandVar.setCachedData(bandArray, false);
    ncfile.addVariable(null, bandVar);

    // the image
    Variable imageVar = new Variable(ncfile, null, null, "image");
    imageVar.setDataType(DataType.INT);
    imageVar.setDimensions(image);
    setCalTypeAttributes(imageVar, getCalType(calName));
    imageVar.addAttribute(new Attribute(getADDescription(AreaFile.AD_CALTYPE), calName));
    imageVar.addAttribute(new Attribute("bands", bandArray));
    imageVar.addAttribute(new Attribute("grid_mapping", "AREAnav"));
    ncfile.addVariable(null, imageVar);


    Variable dirVar = new Variable(ncfile, null, null, "areaDirectory");
    dirVar.setDataType(DataType.INT);
    dirVar.setDimensions("dirSize");
    setAreaDirectoryAttributes(dirVar);
    ArrayInt.D1 dirArray = new ArrayInt.D1(AreaFile.AD_DIRSIZE, false);
    for (int i = 0; i < AreaFile.AD_DIRSIZE; i++) {
      dirArray.set(i, dirBlock[i]);
    }
    dirVar.setCachedData(dirArray, false);
    ncfile.addVariable(null, dirVar);

    Variable navVar = new Variable(ncfile, null, null, "navBlock");
    navVar.setDataType(DataType.INT);
    navVar.setDimensions("navSize");
    setNavBlockAttributes(navVar);
    ArrayInt.D1 navArray = new ArrayInt.D1(navBlock.length, false);
    for (int i = 0; i < navBlock.length; i++) {
      navArray.set(i, navBlock[i]);
    }
    navVar.setCachedData(navArray, false);
    ncfile.addVariable(null, navVar);


    // projection variable
    ProjectionImpl projection = new McIDASAreaProjection(af);
    Variable proj = new Variable(ncfile, null, null, "AREAnav");
    proj.setDataType(DataType.CHAR);
    proj.setDimensions("");

    for (Parameter p : projection.getProjectionParameters()) {
      proj.addAttribute(new Attribute(p));
    }

    // For now, we have to overwrite the parameter versions of thes
    proj.addAttribute(new Attribute("grid_mapping_name", McIDASAreaProjection.GRID_MAPPING_NAME));
    /*
     * proj.addAttribute(new Attribute(McIDASAreaProjection.ATTR_AREADIR,
     * dirArray));
     * proj.addAttribute(new Attribute(McIDASAreaProjection.ATTR_NAVBLOCK,
     * navArray));
     */
    varArray = new ArrayChar.D0();
    ((ArrayChar.D0) varArray).set(' ');
    proj.setCachedData(varArray, false);

    ncfile.addVariable(null, proj);

    // add global attributes
    ncfile.addAttribute(null, new Attribute("Conventions", "CF-1.0"));
    ncfile.addAttribute(null, new Attribute(CF.FEATURE_TYPE, FeatureType.GRID.toString()));
    ncfile.addAttribute(null, new Attribute("nominal_image_time", df.toDateTimeString(nomTime)));

    String encStr = "netCDF encoded on " + df.toDateTimeString(new Date());
    ncfile.addAttribute(null, new Attribute("history", encStr));

    // Lastly, finish the file
    ncfile.finish();
    return true;
  }

  /**
   * Check to see if this is a valid AREA file.
   *
   * @param raf the file in question
   * @return true if it is an AREA file.
   */
  public static boolean isValidFile(RandomAccessFile raf) {
    String fileName = raf.getLocation();
    AreaFile af = null;
    try {
      af = new AreaFile(fileName); // LOOK opening again not ok for isValidFile
      return true;
    } catch (AreaFileException e) {
      return false; // barfola
    } finally {
      if (af != null)
        af.close(); // LOOK need to look at this code
    }
  }


  /**
   * Read the values for a variable
   *
   * @param v2 the variable
   * @param section the section info (time,x,y range);
   * @return the data
   * @throws IOException problem reading file
   * @throws InvalidRangeException range doesn't match data
   */
  public Array readVariable(Variable v2, Section section) throws IOException, InvalidRangeException {
    // not sure why timeRange isn't used...will comment out
    // for now
    // TODO: use timeRange in readVariable
    // Range timeRange = null;
    Range bandRange = null;
    Range geoXRange = null;
    Range geoYRange = null;
    Array dataArray;

    if (section == null) {
      dataArray = Array.factory(v2.getDataType(), v2.getShape());

    } else if (section.getRank() > 0) {
      if (section.getRank() > 3) {
        // timeRange = (Range) section.getRange(0);
        bandRange = section.getRange(1);
        geoYRange = section.getRange(2);
        geoXRange = section.getRange(3);
      } else if (section.getRank() > 2) {
        // timeRange = (Range) section.getRange(0);
        geoYRange = section.getRange(1);
        geoXRange = section.getRange(2);
      } else if (section.getRank() > 1) {
        geoYRange = section.getRange(0);
        geoXRange = section.getRange(1);
      }

      dataArray = Array.factory(v2.getDataType(), section.getShape());

    } else {
      String strRank = Integer.toString(section.getRank());
      String msg = "Invalid Rank: " + strRank + ". Must be > 0.";
      throw new IndexOutOfBoundsException(msg);
    }
    String varname = v2.getFullName();

    Index dataIndex = dataArray.getIndex();

    if (varname.equals("latitude") || varname.equals("longitude")) {
      double[][] pixel = new double[2][1];
      double[][] latLon;

      assert geoXRange != null;
      assert geoYRange != null;

      // Use Range object, which calculates requested i, j
      // values and incorporates stride
      for (int i = 0; i < geoXRange.length(); i++) {
        for (int j = 0; j < geoYRange.length(); j++) {
          pixel[0][0] = (double) geoXRange.element(i);
          pixel[1][0] = (double) geoYRange.element(j);
          latLon = nav.toLatLon(pixel);

          if (varname.equals("latitude")) {
            dataArray.setFloat(dataIndex.set(j, i), (float) (latLon[0][0]));
          } else {
            dataArray.setFloat(dataIndex.set(j, i), (float) (latLon[1][0]));
          }
        }
      }
    }

    if (varname.equals("image")) {
      try {
        int[][] pixelData;
        if (bandRange != null) {
          for (int k = 0; k < bandRange.length(); k++) {
            int bandIndex = bandRange.element(k) + 1; // band numbers in McIDAS are 1 based
            for (int j = 0; j < geoYRange.length(); j++) {
              for (int i = 0; i < geoXRange.length(); i++) {
                pixelData = af.getData(geoYRange.element(j), geoXRange.element(i), 1, 1, bandIndex);
                dataArray.setInt(dataIndex.set(0, k, j, i), (pixelData[0][0]));
              }
            }
          }

        } else {
          assert geoXRange != null;
          assert geoYRange != null;

          for (int j = 0; j < geoYRange.length(); j++) {
            for (int i = 0; i < geoXRange.length(); i++) {
              pixelData = af.getData(geoYRange.element(j), geoXRange.element(i), 1, 1);
              dataArray.setInt(dataIndex.set(0, j, i), (pixelData[0][0]));
            }
          }
        }
      } catch (AreaFileException afe) {
        throw new IOException(afe.toString());
      }

    }

    return dataArray;
  }

  /**
   * Set the area directory attributes on the variable
   *
   * @param v the variable to set them on
   */
  private void setAreaDirectoryAttributes(Variable v) {
    if ((dirBlock == null) || (ad == null)) {
      return;
    }
    for (int i = 1; i < 14; i++) {
      if (i == 7) {
        continue;
      }
      v.addAttribute(new Attribute(getADDescription(i), dirBlock[i]));
    }
  }

  /**
   * Set the navigation block attributes on the variable
   *
   * @param v the variable to set them on
   */
  private void setNavBlockAttributes(Variable v) {
    if ((navBlock == null) || (ad == null)) {
      return;
    }
    v.addAttribute(new Attribute("navigation_type", McIDASUtil.intBitsToString(navBlock[0])));
  }

  // TODO: Move to use ucar.mcidas.AreaDirectory.getDescription
  // once it's released

  /**
   * Get a description for a particular Area Directory entry
   *
   * @param index the index
   * @return a description
   */
  private String getADDescription(int index) {

    String desc = "dir(" + index + ")";
    switch (index) {

      case AreaFile.AD_STATUS:
        desc = "relative position of the image object in the ADDE dataset";
        break;

      case AreaFile.AD_VERSION:
        desc = "AREA version";
        break;

      case AreaFile.AD_SENSORID:
        desc = "SSEC sensor source number";
        break;

      case AreaFile.AD_IMGDATE:
        desc = "nominal year and Julian day of the image (yyyddd)";
        break;

      case AreaFile.AD_IMGTIME:
        desc = "nominal time of the image (hhmmss)";
        break;

      case AreaFile.AD_STLINE:
        desc = "upper-left image line coordinate";
        break;

      case AreaFile.AD_STELEM:
        desc = "upper-left image element coordinate";
        break;

      case AreaFile.AD_NUMLINES:
        desc = "number of lines in the image";
        break;

      case AreaFile.AD_NUMELEMS:
        desc = "number of data points per line";
        break;

      case AreaFile.AD_DATAWIDTH:
        desc = "number of bytes per data point";
        break;

      case AreaFile.AD_LINERES:
        desc = "line resolution";
        break;

      case AreaFile.AD_ELEMRES:
        desc = "element resolution";
        break;

      case AreaFile.AD_NUMBANDS:
        desc = "number of spectral bands";
        break;

      case AreaFile.AD_PFXSIZE:
        desc = "length of the line prefix";
        break;

      case AreaFile.AD_PROJNUM:
        desc = "SSEC project number used when creating the file";
        break;

      case AreaFile.AD_CRDATE:
        desc = "year and Julian day the image file was created (yyyddd)";
        break;

      case AreaFile.AD_CRTIME:
        desc = "image file creation time (hhmmss)";
        break;

      case AreaFile.AD_BANDMAP:
        desc = "spectral band map: bands 1-32";
        break;

      case AreaFile.AD_DATAOFFSET:
        desc = "byte offset to the start of the data block";
        break;

      case AreaFile.AD_NAVOFFSET:
        desc = "byte offset to the start of the navigation block";
        break;

      case AreaFile.AD_VALCODE:
        desc = "validity code";
        break;

      case AreaFile.AD_STARTDATE:
        desc = "actual image start year and Julian day (yyyddd)";
        break;

      case AreaFile.AD_STARTTIME:
        desc = "actual image start time (hhmmss) in milliseconds for POES data";
        break;

      case AreaFile.AD_STARTSCAN:
        desc = "actual image start scan";
        break;

      case AreaFile.AD_DOCLENGTH:
        desc = "length of the prefix documentation";
        break;

      case AreaFile.AD_CALLENGTH:
        desc = "length of the prefix calibration";
        break;

      case AreaFile.AD_LEVLENGTH:
        desc = "length of the prefix band list";
        break;

      case AreaFile.AD_SRCTYPE:
        desc = "source type";
        break;

      case AreaFile.AD_CALTYPE:
        desc = "calibration type";
        break;

      case AreaFile.AD_SRCTYPEORIG:
        desc = "original source type";
        break;

      case AreaFile.AD_CALTYPEUNIT:
        desc = "calibration unit";
        break;

      case AreaFile.AD_CALTYPESCALE:
        desc = "calibration scaling";
        break;

      case AreaFile.AD_AUXOFFSET:
        desc = "byte offset to the supplemental block";
        break;

      case AreaFile.AD_CALOFFSET:
        desc = "byte offset to the calibration block";
        break;

      case AreaFile.AD_NUMCOMMENTS:
        desc = "number of comment cards";
        break;

    }
    desc = desc.replaceAll("\\s", "_");
    return desc;

  }

  /**
   * Get the calibration type from the name
   *
   * @param calName calibration name
   * @return the Calibrator class type
   */
  private int getCalType(String calName) {
    int calTypeOut = Calibrator.CAL_NONE;
    switch (calName.trim()) {
      case "ALB":
        calTypeOut = Calibrator.CAL_ALB;
        break;
      case "BRIT":
        calTypeOut = Calibrator.CAL_BRIT;
        break;
      case "RAD":
        calTypeOut = Calibrator.CAL_RAD;
        break;
      case "RAW":
        calTypeOut = Calibrator.CAL_RAW;
        break;
      case "TEMP":
        calTypeOut = Calibrator.CAL_TEMP;
        break;
    }
    return calTypeOut;
  }

  /**
   * Set the long name and units for the calibration type
   *
   * @param image image variable
   * @param calType calibration type
   */
  private void setCalTypeAttributes(Variable image, int calType) {
    String longName = "image values";
    // String unit = "";
    switch (calType) {

      case Calibrator.CAL_ALB:
        longName = "albedo";
        // unit = "%";
        break;

      case Calibrator.CAL_BRIT:
        longName = "brightness values";
        break;

      case Calibrator.CAL_TEMP:
        longName = "temperature";
        // unit = "K";
        break;

      case Calibrator.CAL_RAD:
        longName = "pixel radiance values";
        // unit = "mW/m2/sr/cm-1";
        break;

      case Calibrator.CAL_RAW:
        longName = "raw image values";
        break;

      default:
        break;
    }
    image.addAttribute(new Attribute("long_name", longName));
    if (calUnit != null) {
      image.addAttribute(new Attribute(CDM.UNITS, calUnit));
    }
    if (calScale != 1.f) {
      image.addAttribute(new Attribute("scale_factor", calScale));
    }

  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("af", af).add("nav", nav).add("ad", ad).add("calibrator", calibrator)
        .add("calScale", calScale).add("calUnit", calUnit).toString();
  }
}

