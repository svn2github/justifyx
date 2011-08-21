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

import java.awt.image.BufferedImage;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import de.felixbruns.jotify.JotifyConnection;
import de.felixbruns.jotify.exceptions.AuthenticationException;
import de.felixbruns.jotify.exceptions.ConnectionException;
import de.felixbruns.jotify.media.Album;
import de.felixbruns.jotify.media.File;
import de.felixbruns.jotify.media.Link;
import de.felixbruns.jotify.media.Playlist;
import de.felixbruns.jotify.media.Track;
import de.felixbruns.jotify.media.User;
import de.felixbruns.jotify.media.Link.InvalidSpotifyURIException;
import de.felixbruns.jotify.player.SpotifyInputStream;

public class Justify extends JotifyConnection{
	
	private static Pattern REGEX = Pattern.compile(":(.*?):");
	private static String TRACK_FORMAT = ":artist.name: - :title:.ogg";
	private static String ALBUM_FORMAT = ":artist.name: - :name:";
	private static String PLAYLIST_FORMAT = ":author: - :name:";
	private static long TIMEOUT = 10; // en segundos
	private static Integer songindex = 1;

	public static void main(String args[]){
		
		if (args.length != 3){
			System.err.println("[ERROR] Se esperan 3 parametros: nombre de usuario, password y direccion Spotify para descargar");
			return;
		}
		
		Justify justify = new Justify();
		try{
			try{ justify.login(args[0], args[1]);
			}catch(ConnectionException ce){ throw new JustifyException("[ERROR] No se ha podido conectar con el servidor");
			}catch(AuthenticationException ae){ throw new JustifyException("[ERROR] Usuario o password no validos"); }

			User usuario = justify.user();
			System.out.println(usuario);
			if (!usuario.isPremium()) throw new JustifyException("[ERROR] Debes ser usuario 'premium'");
			try{
				Link uri = Link.create(args[2]);
				
				if (uri.isTrackLink()){
					
					Track track = justify.browseTrack(uri.getId());
					if (track == null) throw new JustifyException("[ERROR] Pista no encontrada");
					justify.downloadTrack(track, null);
					
				}else if (uri.isPlaylistLink()){
					
					Playlist playlist = justify.playlist(uri.getId());
					if (playlist == null) throw new JustifyException("[ERROR] Lista de reproduccion no encontrada");
					System.out.println(playlist);
					System.out.println("Numero de pistas: " + playlist.getTracks().size());
					String directorio = replaceByReference(playlist, PLAYLIST_FORMAT);
					for(Track track : playlist.getTracks()) justify.downloadTrack(justify.browse(track), directorio);
					
				}else if(uri.isAlbumLink()){
					
					Album album = justify.browseAlbum(uri.getId());
					if (album == null) throw new JustifyException("[ERROR] Album no encontrado");
					System.out.println(album);
					System.out.println("Contiene " + album.getTracks().size() + " pistas repartidas en " + album.getDiscs().size() + " disco(s)");
					String directorio = replaceByReference(album, ALBUM_FORMAT);
					for(Track track : album.getTracks()) justify.downloadTrack(track, directorio);
					
					// retrieve album cover
					try{
						URL coverurl = new URL("http://o.scdn.co/image/" + album.getCover());
						BufferedImage coverimage = ImageIO.read(coverurl);
						java.io.File coverfile = new java.io.File(directorio, "cover.jpg");
						ImageIO.write(coverimage, "jpg", coverfile);
					} catch(IOException e){
						e.printStackTrace();
					}		
							
				}else throw new JustifyException("[ERROR] Se esperaba una pista, album o lista de reproduccion");
				
			}catch (InvalidSpotifyURIException urie){ throw new JustifyException("[ERROR] Direccion de Spotify no valida"); }
				
		}catch (JustifyException je){ System.err.println(je.getMessage()); je.printStackTrace();
		}catch (TimeoutException te){ System.err.println(te.getMessage()); te.printStackTrace();
		}finally{
			try{ justify.close();
			}catch (ConnectionException ce){ System.err.println("[ERROR] No se ha podido desconectar"); } 
		}
	}

	public Justify(){ super(TIMEOUT, TimeUnit.SECONDS); }

	private void downloadTrack(Track track, String parent) throws JustifyException, TimeoutException{
		System.out.println(track);
		try{
			String nombre = replaceByReference(track, TRACK_FORMAT);
			String indexstring;
			if(songindex<10)
				indexstring = "0" + songindex.toString();
			else
				indexstring = songindex.toString();
			java.io.File file = new java.io.File(sanearNombre(parent), indexstring + " " + sanearNombre(nombre));
			System.out.println("Descargando al fichero " + file.getPath());
			if(parent != null && !file.getParentFile().exists()) file.getParentFile().mkdirs();
				download(track, file, File.BITRATE_320); // bitrate maximo disponible
			songindex++;
		}catch(FileNotFoundException fnfe){ fnfe.printStackTrace(); /* throw new JustifyException("[ERROR] No se ha podido guardar el archivo"); */
		}catch(IOException ioe){ ioe.printStackTrace(); /* throw new JustifyException("[ERROR] Ha ocurrido un fallo de entrada / salida"); */ }

	}
	
	private void download(Track track, java.io.File file, int bitrate) throws TimeoutException, IOException{

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
