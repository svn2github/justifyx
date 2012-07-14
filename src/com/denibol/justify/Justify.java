/*
    This file is part of Justify.

    Justify is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Justify is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with Foobar.  If not, see <http://www.gnu.org/licenses/>.
*/

package com.denibol.justify;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.text.DecimalFormat;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.FileImageOutputStream;

import de.felixbruns.jotify.JotifyConnection;
import de.felixbruns.jotify.exceptions.AuthenticationException;
import de.felixbruns.jotify.exceptions.ConnectionException;
import de.felixbruns.jotify.media.Album;
import de.felixbruns.jotify.media.Link;
import de.felixbruns.jotify.media.Playlist;
import de.felixbruns.jotify.media.Track;
import de.felixbruns.jotify.media.User;
import de.felixbruns.jotify.media.Link.InvalidSpotifyURIException;
import de.felixbruns.jotify.player.SpotifyInputStream;

import adamb.vorbis.CommentField;
import adamb.vorbis.VorbisCommentHeader;
import adamb.vorbis.VorbisIO;

public class Justify extends JotifyConnection{
	private static Pattern REGEX = Pattern.compile(":(.*?):");
	private static String ALBUM_FORMAT = ":artist.name: - :name:";
	private static String PLAYLIST_FORMAT = ":author: - :name:";
	private static long TIMEOUT = 20; // in seconds
	private static Integer discindex = 1;
	private static Integer oldtracknumber = 1;
	private static String user;
	private static String password;
	static String formataudio;
	static String commandarg;
	static String numbersong;
	static String country;
	
	public static void main(String args[]) throws IOException, InterruptedException{
		
		if (args.length < 5 || args.length > 6 ){
			System.err.println("[ERROR] Parameters: Spotify_user Spotify_password Spotify_URI Format Command");
			System.err.println("Format:");
			System.err.println("    ogg_96:  Ogg Vorbis @ 96kbps");
			System.err.println("    ogg_160: Ogg Vorbis @ 160kbps");
			System.err.println("    ogg_320: Ogg Vorbis @ 320kbps");
			System.err.println("Command:");
			System.err.println("    download: downloads track/list/album");
			System.err.println("    download number: downloads an album starting on the specified track number");
			System.err.println("    cover: downloads album cover");
			return;
		}
		
		user = args[0];
		password = args[1];
		formataudio = args[3];
		commandarg = args[4];
		if (args.length == 6) numbersong = args[5];
		
		Justify justify = new Justify();
		try{
			try{ justify.login(user, password);
			}catch(ConnectionException ce){ throw new JustifyException("[ERROR] Error connecting the server");
			}catch(AuthenticationException ae){ throw new JustifyException("[ERROR] User or password is not valid"); }

			User usuario = justify.user();
			country = usuario.getCountry();
			System.out.println(usuario);
			System.out.println();
			if (!usuario.isPremium()) throw new JustifyException("[ERROR] You must be a 'premium' user");
			try{
				Link uri = Link.create(args[2]);

				if(commandarg.equals("download")) {
					if (uri.isTrackLink()){
						
						Track track = justify.browseTrack(uri.getId());
						if (track == null) throw new JustifyException("[ERROR] Track not found");
						justify.downloadTrack(track, null, formataudio, "track", 0);
						
					}else if (uri.isPlaylistLink()){
						
						Playlist playlist = justify.playlist(uri.getId());
						if (playlist == null) throw new JustifyException("[ERROR] Playlist not found");
						System.out.println("Playlist: " + playlist.getName() + " | Author: " + playlist.getAuthor() + " | Tracks: " + playlist.getTracks().size());
						System.out.println();
						String directorio = replaceByReference(playlist, PLAYLIST_FORMAT);
						DecimalFormat f = new DecimalFormat( "00" );
						Integer indexplaylist = 1;
						for(Track track : playlist.getTracks()) {
							System.out.print("[" + f.format((indexplaylist - 1) * 100 / playlist.getTracks().size()) + "%] ");							
							justify.downloadTrack(justify.browse(track), directorio, formataudio, "playlist", indexplaylist);
							indexplaylist++;
						}
						indexplaylist = 0;
						System.out.println("[100%] Playlist downloaded");
							
					}else if(uri.isAlbumLink()){
							Album album = justify.browseAlbum(uri.getId());
							if (album == null) throw new JustifyException("[ERROR] Album not found");
							System.out.println("Album: " + album.getName() + " | Artist: " + album.getArtist().getName() + " | Tracks: " + album.getTracks().size() +" | Discs: " + album.getDiscs().size());
							System.out.println();
							String directorio = replaceByReference(album, ALBUM_FORMAT);
							for(Track track : album.getTracks()){
								boolean downloaded = false;
								Integer retries = 5;
								Integer counter = 0;
								while(!downloaded && (counter<retries)) {		
									counter++;
									try {
										if (args.length == 5 || (args.length == 6 && track.getTrackNumber() >= Integer.parseInt(numbersong))) {
											justify.downloadTrack(track, directorio, formataudio, "album", 0);
											downloaded = true;
										}
									} catch (TimeoutException te1){
										if(counter != retries)
											System.out.println("  <-  Timeout! Trying again in 5 secs... (x" + counter.toString() + ")");
										else
											System.out.println("  <-  Timeout! Skipping track...");
										
										Thread.sleep(5000);
									}
								}
							}
							justify.downloadCover(justify.image(album.getCover()), directorio);			
					} else throw new JustifyException("[ERROR] Track, album or playlist not specified");
				} else if(commandarg.equals("cover")){
					if(uri.isAlbumLink()){
						Album album = justify.browseAlbum(uri.getId());
						if (album == null) throw new JustifyException("[ERROR] Album not found");
						System.out.println("Album: " + album.getName() + " | Artist: " + album.getArtist().getName());
						System.out.println();
						String directorio = replaceByReference(album, ALBUM_FORMAT);
						justify.downloadCover(justify.image(album.getCover()), directorio);			
					}
				}
			}catch (InvalidSpotifyURIException urie){ throw new JustifyException("[ERROR] Spotify URI is not valid"); }
				
		}catch (JustifyException je){ System.err.println(je.getMessage()); je.printStackTrace();
		}catch (TimeoutException te){ System.err.println(te.getMessage()); te.printStackTrace();
		}finally{
			try{ justify.close();
			}catch (ConnectionException ce){ System.err.println("[ERROR] Problem disconnecting"); } 
		}
	}
 
	public Justify(){ super(TIMEOUT, TimeUnit.SECONDS); }
	
	private void downloadCover(Image image, String parent) throws TimeoutException, IOException {
		Iterator<ImageWriter> iter = ImageIO.getImageWritersByFormatName("jpeg");
		ImageWriter writer = (ImageWriter)iter.next();
		ImageWriteParam iwp = writer.getDefaultWriteParam();
		iwp.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
		iwp.setCompressionQuality(1);
		java.io.File coverfile = new java.io.File(sanearNombre(parent), "folder.jpg");
		coverfile.getParentFile().mkdirs();
		FileImageOutputStream output = new FileImageOutputStream(coverfile);
		writer.setOutput(output);
		IIOImage iimage = new IIOImage((BufferedImage) image, null, null);
		writer.write(null, iimage, iwp);
		writer.dispose();
		System.out.println("[100%] Album cover  <-  OK!");
	}

	private void downloadTrack(Track track, String parent, String bitrate, String option, Integer indexplaylist) throws JustifyException, TimeoutException{	
		if(track.getAlbum().getDiscs().size() > 1) {
			if(track.getTrackNumber() < oldtracknumber) {
				discindex++;
				oldtracknumber = 1;
			} else oldtracknumber = track.getTrackNumber();
		}
		
		try{
			String nombre="";
			
			if(option.equals("album"))
				nombre = (track.getAlbum().getDiscs().size() > 1 ? discindex : "") + (track.getTrackNumber() < 10 ? "0" : "") + track.getTrackNumber() + " ";
			else if (option.equals("playlist"))
				nombre = (indexplaylist < 10 ? "0" : "") + indexplaylist.toString() + " ";
				
			nombre =  nombre + track.getAlbum().getArtist().getName() + " - " + track.getTitle() + ".ogg";
			java.io.File file = new java.io.File(sanearNombre(parent), sanearNombre(nombre));
			DecimalFormat f = new DecimalFormat( "00" );
			
			if(option.equals("playlist"))
				System.out.print(sanearNombre(nombre));
			else if (option.equals("album"))
				System.out.print("[" + f.format((track.getTrackNumber() - 1) * 100 / track.getAlbum().getTracks().size()) + "%] " + sanearNombre(nombre));
			else if (option.equals("track"))
				System.out.print(sanearNombre(nombre));
			
			if(parent != null && !file.getParentFile().exists()) file.getParentFile().mkdirs();

			boolean allowed = true;
			Integer nalternative = 0;
            Integer talternative = 0;
            
            if(track.getRestrictions().get(0).getForbidden() != null)
                    if(track.getRestrictions().get(0).getForbidden().contains(country) == true) {
                            allowed = false;
                    }
                                    
            if(track.getRestrictions().get(0).getAllowed() != null)
                    if (track.getRestrictions().get(0).getAllowed().contains(country) == false) {
                            allowed = false;
                    }
            
            if (allowed == false) {
                    for(Track pista : track.getAlternatives()) {
                            nalternative++;
                            if(pista.getRestrictions().get(0).getForbidden() != null)
                                    if(pista.getRestrictions().get(0).getForbidden().contains(country) == true) {
                                            allowed = false;
                                    }
                                    else {
                                    	allowed = true;
                                    	talternative++;
                                    }
                            
                            if(pista.getRestrictions().get(0).getAllowed() != null) {
                                    if (pista.getRestrictions().get(0).getAllowed().contains(country) == true) {
                                            allowed = true;
                                            talternative = nalternative;
                                    }
                            }
                    }
            }
			
            if (allowed && nalternative == 0) download(track, file, bitrate);
            else {
                    if (allowed && nalternative > 0 ) download(track.getAlternatives().get(talternative-1), file, bitrate); else {
                    	System.out.println("  <-  KO! Region " + country + " not allowed");
                    	return;
                    }
            }

			try {
				VorbisCommentHeader comments = new VorbisCommentHeader();
				
				// Embeds cover in .ogg for tracks and playlists (not albums)
				if(option.equals("track") || option.equals("playlist")) {
					byte[] imagedata = null;
					try{
						BufferedImage image = (BufferedImage) this.image(track.getCover());
						ByteArrayOutputStream output = new ByteArrayOutputStream();
						ImageIO.write(image, ImageFormats.getFormatForMimeType(ImageFormats.MIME_TYPE_JPG), new DataOutputStream(output));
						imagedata = output.toByteArray();
					}catch(Exception e) { e.printStackTrace(); }
					if (imagedata != null){
						char[] testdata = Base64Coder.encode(imagedata);
						String base64image = new String(testdata);
						//doc: embedded artwork vorbis standards: http://wiki.xiph.org/VorbisComment#Cover_art
						boolean use_new_method = true;
						boolean use_old_method = false;
						if(use_old_method){
							comments.fields.add(new CommentField("COVERART",base64image));
							comments.fields.add(new CommentField("COVERARTMIME",ImageFormats.MIME_TYPE_JPG));
						}
						if(use_new_method){
							comments.fields.add(new CommentField("METADATA_BLOCK_PICTURE",base64image));
						}
					}				
				}
				
				comments.fields.add(new CommentField("ARTIST", track.getArtist().getName()));
				comments.fields.add(new CommentField("ALBUM ARTIST", track.getAlbum().getArtist().getName()));
				comments.fields.add(new CommentField("ALBUM", track.getAlbum().getName()));
				comments.fields.add(new CommentField("TITLE", track.getTitle()));
				comments.fields.add(new CommentField("DATE", String.valueOf(track.getYear())));
				comments.fields.add(new CommentField("TRACKNUMBER", String.valueOf(track.getTrackNumber())));
				comments.fields.add(new CommentField("DISCNUMBER", discindex.toString()));
				if (option.equals("album"))
					comments.fields.add(new CommentField("TOTALDISCS", String.valueOf(track.getAlbum().getDiscs().size())));
				VorbisIO.writeComments(file, comments);
			} catch (IOException e) { e.printStackTrace(); }
			
			System.out.println("  <-  OK!");
		}catch(FileNotFoundException fnfe){ fnfe.printStackTrace();
		}catch(IOException ioe){ ioe.printStackTrace();
		}
	}

	private void download(Track track, java.io.File file, String bitrate) throws TimeoutException, IOException{
		if (track.getFiles().size() == 0) return;
		FileOutputStream fos = new FileOutputStream(file);
		SpotifyInputStream sis = new SpotifyInputStream(protocol, track, bitrate);

		byte[] buf = new byte[8192];
		sis.read(buf, 0, 167); // Skip Spotify OGG Header, no se puede usar skip() porque aun no hay datos leidos por ningun read()

		while (true) {
			int length = sis.read(buf);
			if (length < 0) break;
			fos.write(buf, 0, length);
		}
		
		sis.close();
		fos.close();
		
	}

	public static boolean isWindows(){
		String os = System.getProperty("os.name").toLowerCase();
		return (os.indexOf( "win" ) >= 0); 
	}

	public static String sanearNombre(String nombre){
		if(nombre==null) return null;

		if(isWindows())
			nombre = nombre.replaceAll("\\\\", "/");
		else
			nombre = nombre.replaceAll("/", "\\\\");

		nombre = nombre.replaceAll("[\\\\/:*?\"<>|]", "_");

		return nombre;
	}

	public static String capitalize(String s) { return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase(); }

	public static String replaceByReference(Object inicial, String formato) throws JustifyException{
		StringBuffer resultString = new StringBuffer();
		try {

			Matcher regexMatcher = REGEX.matcher(formato);
			while (regexMatcher.find()) {
				String referencias = regexMatcher.group();
				referencias = referencias.substring(1, referencias.length() - 1);
				String[] metodos = referencias.split("\\.");
				Object objeto = inicial;
				for (String metodo : metodos){

					Class<?> clase = objeto.getClass();
					Method method = clase.getMethod("get" + capitalize(metodo), new Class<?>[0]);
					objeto = method.invoke(objeto, new Object[0]);
				}

				regexMatcher.appendReplacement(resultString, (String)objeto);
			}
			regexMatcher.appendTail(resultString);
		} catch (Exception e){ throw new JustifyException(e); }
		return resultString.toString();
    }
	
}
