/*
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2007 - 2022 Fiji developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */
package mpicbg.stitching;

import ij.IJ;
import ij.gui.Roi;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.awt.Rectangle;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicInteger;

import stitching.utils.Log;
import mpicbg.imglib.multithreading.SimpleMultiThreading;
import mpicbg.imglib.util.Util;
import mpicbg.models.TranslationModel2D;
import mpicbg.models.TranslationModel3D;

public class CollectionStitchingImgLib 
{

	public static void normalizeIntensity(ArrayList<ImageCollectionElement> elements, final StitchingParameters params) {

		int numChannels = elements.get(0).open(false).getNChannels(); // Example to get number of channels
        int numFOVs = elements.get(0).open(false).getNSlices(); // Assuming the number of slices represents FOVs per channel
        
        if (elements.isEmpty()) return;
        
        final AtomicInteger ai = new AtomicInteger(0);
		
		final int numThreads;
		
		if ( params.cpuMemChoice == 0 )
			numThreads = 1;
		else
			numThreads = Runtime.getRuntime().availableProcessors();

        // Assuming all images have the same dimensions
        ImagePlus firstImage = elements.get(0).open(false);
        final int width = firstImage.getWidth();
        final int height = firstImage.getHeight();
        int sliceCount = (numFOVs*numChannels) * elements.size();
        // To store median images for each channel
        ImagePlus[] medianImages = new ImagePlus[numChannels];
		ImageProcessor[] slices = new ImageProcessor[sliceCount];
		
		for (int elementIndex = 0; elementIndex<elements.size();elementIndex++) {
			ImageStack stack = elements.get(elementIndex).open(false).getStack();
			for (int slice=0; slice<numFOVs*numChannels; slice++) {
				slices[slice + elementIndex*numFOVs*numChannels] = stack.getProcessor(slice+1);
			}
		}
		
		final Thread[] threads = SimpleMultiThreading.newThreads( numThreads );
		for ( int ithread = 0; ithread < threads.length; ++ithread ) {
            threads[ ithread ] = new Thread(new Runnable()
            {
                @Override
                public void run()
                {		
                   	final int myNumber = ai.getAndIncrement();
			        for (int c = 0; c < numChannels; c++) {
                    	if ( c % numThreads != myNumber ) {
                    		continue;
                    	}
			        	
			            float[] allPixels = new float[width * height];
			            // Initialize an empty median image for this channel
			            ImageProcessor medianProcessor = new ij.process.FloatProcessor(width, height);
			            
			            // Prepare an array to hold pixel values for the median calculation
			            float[] pixelValues = new float[numFOVs* elements.size()];
			
			            for (int y = 0; y < height; y++) {
			                for (int x = 0; x < width; x++) {
			                    int index = 0;
			                    for (int fov = 0; fov < sliceCount/numChannels; fov++) {
			                        pixelValues[index++] = slices[c + fov*numChannels].getPixelValue(x, y);
			                    }
			                    // Calculate median for this pixel
			                    float median = calculateMedian(pixelValues, index);
			                    medianProcessor.putPixelValue(x, y, median);
			                    allPixels[x+y*height] = median;
			                }
			            }
			            float median = calculateMedian(allPixels,width * height);
			
			            for (int y = 0; y < height; y++) {
			                for (int x = 0; x < width; x++) {
			                	medianProcessor.putPixelValue(x, y, (float) medianProcessor.getPixelValue(x, y)/median);
			                }
			            }
			
			            // Create ImagePlus for the median image of the current channel
			            medianImages[c] = new ImagePlus("Median Image - Channel " + (c + 1), medianProcessor);

						ImageProcessor normIP = medianImages[c].getProcessor();
			    		for (int y = 0; y < height; y++) {
			                for (int x = 0; x < width; x++) {
			                    for (int fov = 0; fov < sliceCount/numChannels; fov++) {
			                    	int newVal = (int) ((float) slices[c + fov*numChannels].getPixelValue(x, y)/normIP.getPixelValue(x, y));
			                        slices[c + fov*numChannels].putPixelValue(x, y, newVal);
			                    }
			                }
			            }
			        }
                }
            });
		}
		SimpleMultiThreading.startAndJoin( threads );
		int b = 0;
    }

    // Helper function to calculate the median of a float array
    private static float calculateMedian(float[] values, int length) {
        Arrays.sort(values);
        int middle = length / 2;
        if (length % 2 == 0) // even number of elements
            return (values[middle - 1] + values[middle]) / 2f;
        else
            return values[middle];
    }
	
	public static ArrayList< ImagePlusTimePoint > stitchCollection( final ArrayList< ImageCollectionElement > elements, final StitchingParameters params )
	{
		if (params.normalizeFOV) 
			normalizeIntensity(elements,params);
		
		// the result
		final ArrayList< ImagePlusTimePoint > optimized;
		
		if ( params.computeOverlap )
		{
			// find overlapping tiles
			final Vector< ComparePair > pairs = findOverlappingTiles( elements, params );
			
			if ( pairs == null || pairs.size() == 0 )
			{
				Log.error( "No overlapping tiles could be found given the approximate layout." );
				return null;
			}
			
			// compute all compare pairs
			// compute all matchings
			final AtomicInteger ai = new AtomicInteger(0);
			
			final int numThreads;
			
			if ( params.cpuMemChoice == 0 )
				numThreads = 1;
			else
				numThreads = Runtime.getRuntime().availableProcessors();
			
	        final Thread[] threads = SimpleMultiThreading.newThreads( numThreads );
	    	
	        for ( int ithread = 0; ithread < threads.length; ++ithread )
	            threads[ ithread ] = new Thread(new Runnable()
	            {
	                @Override
	                public void run()
	                {		
	                   	final int myNumber = ai.getAndIncrement();
	                    
	                    for ( int i = 0; i < pairs.size(); i++ )
	                    {
	                    	if ( i % numThreads == myNumber )
	                    	{
	                    		final ComparePair pair = pairs.get( i );
	                    		
	                    		long start = System.currentTimeMillis();			
	                			
	                    		// where do we approximately overlap?
	                			final Roi roi1 = getROI( pair.getTile1().getElement(), pair.getTile2().getElement() );
	                			final Roi roi2 = getROI( pair.getTile2().getElement(), pair.getTile1().getElement() );
	                			
	            				final PairWiseStitchingResult result = PairWiseStitchingImgLib.stitchPairwise( pair.getImagePlus1(), pair.getImagePlus2(), roi1, roi2, pair.getTimePoint1(), pair.getTimePoint2(), params );			
	            				if ( result == null )
	            				{
	            					Log.error( "Collection stitching failed" );
	            					return;
	            				}
	
	            				if ( params.dimensionality == 2 )
	            					pair.setRelativeShift( new float[]{ result.getOffset( 0 ), result.getOffset( 1 ) } );
	            				else
	            					pair.setRelativeShift( new float[]{ result.getOffset( 0 ), result.getOffset( 1 ), result.getOffset( 2 ) } );
	            				
	            				pair.setCrossCorrelation( result.getCrossCorrelation() );
	
	            				Log.info( pair.getImagePlus1().getTitle() + "[" + pair.getTimePoint1() + "]" + " <- " + pair.getImagePlus2().getTitle() + "[" + pair.getTimePoint2() + "]" + ": " + 
	            						Util.printCoordinates( result.getOffset() ) + " correlation (R)=" + result.getCrossCorrelation() + " (" + (System.currentTimeMillis() - start) + " ms)");
	                    	}
	                    }
	                }
	            });
	        
	        final long time = System.currentTimeMillis();
	        SimpleMultiThreading.startAndJoin( threads );
	        
	        // get the final positions of all tiles
			optimized = GlobalOptimization.optimize( pairs, pairs.get( 0 ).getTile1(), params );
			Log.info( "Finished registration process (" + (System.currentTimeMillis() - time) + " ms)." );
		}
		else
		{
			// all ImagePlusTimePoints, each of them needs its own model
			optimized = new ArrayList< ImagePlusTimePoint >();
			
			for ( final ImageCollectionElement element : elements )
			{
				final ImagePlusTimePoint imt = new ImagePlusTimePoint( element.open( params.virtual ), element.getIndex(), 1, element.getModel(), element );
				
				// set the models to the offset
				if ( params.dimensionality == 2 )
				{
					final TranslationModel2D model = (TranslationModel2D)imt.getModel();
					model.set( element.getOffset( 0 ), element.getOffset( 1 ) );
				}
				else
				{
					final TranslationModel3D model = (TranslationModel3D)imt.getModel();
					model.set( element.getOffset( 0 ), element.getOffset( 1 ), element.getOffset( 2 ) );					
				}
				
				optimized.add( imt );
			}
			
		}
		
		return optimized;
	}

	protected static Roi getROI( final ImageCollectionElement e1, final ImageCollectionElement e2 )
	{
		final int start[] = new int[ 2 ], end[] = new int[ 2 ];
		
		for ( int dim = 0; dim < 2; ++dim )
		{			
			// begin of 2 lies inside 1
			if ( e2.offset[ dim ] >= e1.offset[ dim ] && e2.offset[ dim ] <= e1.offset[ dim ] + e1.size[ dim ] )
			{
				start[ dim ] = Math.round( e2.offset[ dim ] - e1.offset[ dim ] );
				
				// end of 2 lies inside 1
				if ( e2.offset[ dim ] + e2.size[ dim ] <= e1.offset[ dim ] + e1.size[ dim ] )
					end[ dim ] = Math.round( e2.offset[ dim ] + e2.size[ dim ] - e1.offset[ dim ] );
				else
					end[ dim ] = Math.round( e1.size[ dim ] );
			}
			else if ( e2.offset[ dim ] + e2.size[ dim ] <= e1.offset[ dim ] + e1.size[ dim ] ) // end of 2 lies inside 1
			{
				start[ dim ] = 0;
				end[ dim ] = Math.round( e2.offset[ dim ] + e2.size[ dim ] - e1.offset[ dim ] );
			}
			else // if both outside then the whole image 
			{
				start[ dim ] = -1;
				end[ dim ] = -1;
			}
		}
		
		return new Roi( new Rectangle( start[ 0 ], start[ 1 ], end[ 0 ] - start[ 0 ], end[ 1 ] - start[ 1 ] ) );
	}

	protected static Vector< ComparePair > findOverlappingTiles( final ArrayList< ImageCollectionElement > elements, final StitchingParameters params )
	{		
		for ( final ImageCollectionElement element : elements )
		{
			if ( element.open( params.virtual ) == null )
				return null;
		}
		
		// all ImagePlusTimePoints, each of them needs its own model
		final ArrayList< ImagePlusTimePoint > listImp = new ArrayList< ImagePlusTimePoint >();
		for ( final ImageCollectionElement element : elements )
			listImp.add( new ImagePlusTimePoint( element.open( params.virtual ), element.getIndex(), 1, element.getModel(), element ) );
	
		// get the connecting tiles
		final Vector< ComparePair > overlappingTiles = new Vector< ComparePair >();
		
		// Added by John Lapage: if the sequential option has been chosen, pair up each image 
		// with the images within the specified range, and return.
		if ( params.sequential )
		{
			for ( int i = 0; i < elements.size(); i++ )
			{
				for ( int j = 1 ; j <= params.seqRange ; j++ )
				{
					if ( ( i + j ) >= elements.size() ) 
						break;
					
					overlappingTiles.add( new ComparePair( listImp.get( i ), listImp.get( i+j ) ) );
				}
			}
			return overlappingTiles;
		}
		// end of addition

		for ( int i = 0; i < elements.size() - 1; i++ )
			for ( int j = i + 1; j < elements.size(); j++ )
			{
				final ImageCollectionElement e1 = elements.get( i );
				final ImageCollectionElement e2 = elements.get( j );
				
				boolean overlapping = true;
				
				for ( int d = 0; d < params.dimensionality; ++d )
				{
					if ( !( ( e2.offset[ d ] >= e1.offset[ d ] && e2.offset[ d ] <= e1.offset[ d ] + e1.size[ d ] ) || 
						    ( e2.offset[ d ] + e2.size[ d ] >= e1.offset[ d ] && e2.offset[ d ] + e2.size[ d ] <= e1.offset[ d ] + e1.size[ d ] ) ||
						    ( e2.offset[ d ] <= e1.offset[ d ] && e2.offset[ d ] >= e1.offset[ d ] + e1.size[ d ] ) 
					   )  )
									overlapping = false;
				}
				
				if ( overlapping )
				{
					//final ImagePlusTimePoint impA = new ImagePlusTimePoint( e1.open(), e1.getIndex(), 1, e1.getModel().copy(), e1 );
					//final ImagePlusTimePoint impB = new ImagePlusTimePoint( e2.open(), e2.getIndex(), 1, e2.getModel().copy(), e2 );
					overlappingTiles.add( new ComparePair( listImp.get( i ), listImp.get( j ) ) );
				}
			}
		
		return overlappingTiles;
	}
}
