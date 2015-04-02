package de.paul_woitaschek.audiobook.service;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.IBinder;
import android.support.annotation.Nullable;

import com.squareup.picasso.Picasso;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.paul_woitaschek.audiobook.model.Book;
import de.paul_woitaschek.audiobook.model.Bookmark;
import de.paul_woitaschek.audiobook.model.Chapter;
import de.paul_woitaschek.audiobook.model.NaturalOrderComparator;
import de.paul_woitaschek.audiobook.uitools.ImageHelper;
import de.paul_woitaschek.audiobook.utils.BaseApplication;
import de.paul_woitaschek.audiobook.utils.L;
import de.paul_woitaschek.audiobook.utils.PrefsManager;

public class BookAddingService extends Service {

    public static final FileFilter folderAndMusicFilter = new FileFilter() {
        @Override
        public boolean accept(File pathname) {
            return isAudio(pathname) || pathname.isDirectory();
        }
    };
    private static final String ACTION_UPDATE_BOOKS = "actionUpdateBooks";
    private static final String ACTION_UPDATE_BOOKS_INTERRUPTING = "actionUpdateBooksInterrupting";

    private static final String TAG = BookAddingService.class.getSimpleName();
    private static final ArrayList<String> audioTypes = new ArrayList<>();
    private static final ArrayList<String> imageTypes = new ArrayList<>();

    static {
        audioTypes.add(".3gp");
        audioTypes.add(".mp4");
        audioTypes.add(".m4a");
        audioTypes.add(".m4b");
        audioTypes.add(".mp3");
        audioTypes.add(".mid");
        audioTypes.add(".xmf");
        audioTypes.add(".mxmf");
        audioTypes.add(".rtttl");
        audioTypes.add(".rtx");
        audioTypes.add(".ota");
        audioTypes.add(".imy");
        audioTypes.add(".ogg");
        audioTypes.add(".oga");
        audioTypes.add(".wav");
        audioTypes.add(".aac");
        audioTypes.add(".flac");
        audioTypes.add(".mkv");
        if (Build.VERSION.SDK_INT >= 21) {
            audioTypes.add(".opus");
        }

        imageTypes.add(".jpg");
        imageTypes.add(".jpeg");
        imageTypes.add(".bmp");
        imageTypes.add(".png");
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private BaseApplication baseApplication;
    private PrefsManager prefs;
    private volatile boolean stopScanner = false;

    public static Intent getRescanIntent(Context c, boolean interrupting) {
        Intent i = new Intent(c, BookAddingService.class);
        i.setAction(ACTION_UPDATE_BOOKS);
        i.putExtra(ACTION_UPDATE_BOOKS_INTERRUPTING, interrupting);
        return i;
    }

    private static boolean isAudio(File f) {
        for (String s : audioTypes) {
            if (f.getName().toLowerCase().endsWith(s)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        baseApplication = (BaseApplication) getApplication();
        prefs = new PrefsManager(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null && intent.getAction().equals(ACTION_UPDATE_BOOKS)) {
            boolean interrupting = intent.getBooleanExtra(ACTION_UPDATE_BOOKS_INTERRUPTING, true);
            scanForFiles(interrupting);
        }

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        baseApplication.setScannerActive(false);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void addNewBooks() {
        ArrayList<File> containingFiles = getContainingFiles();
        for (final File f : containingFiles) {
            addNewBook(f);
        }
    }

    private void scanForFiles(boolean interrupting) {
        L.d(TAG, "scanForFiles called. scannerActive=" + baseApplication.isScannerActive() + ", interrupting=" + interrupting);
        if (!baseApplication.isScannerActive() || interrupting) {
            stopScanner = true;
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    baseApplication.setScannerActive(true);
                    stopScanner = false;

                    deleteOldBooks();
                    addNewBooks();

                    stopScanner = false;
                    baseApplication.setScannerActive(false);
                }
            });
        }
    }

    private boolean isImage(File f) {
        for (String s : imageTypes) {
            if (f.getName().toLowerCase().endsWith(s)) {
                return true;
            }
        }
        return false;
    }

    private ArrayList<File> getContainingFiles() {
        // getting all files who are in the root of the chosen folders
        ArrayList<String> folders = prefs.getAudiobookFolders();
        ArrayList<File> containingFiles = new ArrayList<>();
        for (String s : folders) {
            File f = new File(s);
            if (f.exists() && f.isDirectory()) {
                File[] containing = f.listFiles(folderAndMusicFilter);
                if (containing != null) {
                    containingFiles.addAll(Arrays.asList(containing));
                }
            }
        }
        return containingFiles;
    }

    private void deleteOldBooks() {
        ArrayList<File> containingFiles = getContainingFiles();

        //getting books to remove
        baseApplication.bookLock.lock();
        try {
            ArrayList<Book> booksToRemove = new ArrayList<>();
            for (Book book : baseApplication.getAllBooks()) {
                boolean bookExists = false;
                for (File f : containingFiles) {
                    if (f.isDirectory()) { // multi file book
                        if (book.getRoot().equals(f.getAbsolutePath())) {
                            bookExists = true;
                        }
                    } else if (f.isFile()) { // single file book
                        ArrayList<Chapter> chapters = book.getChapters();
                        String singleBookChapterPath = book.getRoot() + "/" + chapters.get(0).getPath();
                        if (singleBookChapterPath.equals(f.getAbsolutePath())) {
                            bookExists = true;
                        }
                    }
                }
                if (!bookExists) {
                    booksToRemove.add(book);
                }
            }

            for (Book b : booksToRemove) {
                L.d(TAG, "deleting book=" + b);
                baseApplication.deleteBook(b);
            }
        } finally {
            baseApplication.bookLock.unlock();
        }
    }

    private void addNewBook(File f) {
        Book bookExisting = getBookByRoot(f);
        Book newBook = rootFileToBook(f);

        // this check is important
        if (stopScanner) {
            return;
        }

        // delete old book if it exists and is different from the new book
        if (!stopScanner && (bookExisting != null && (newBook == null || !newBook.equals(bookExisting)))) {
            L.d(TAG, "addNewBook deletes existing book=" + bookExisting + " because it is different from newBook=" + newBook);
            baseApplication.deleteBook(bookExisting);
        }

        // if there are no changes, we can skip this one
        // skip it if there is no new book or if there is a new and an old book and they are not the same.
        if (newBook == null || (bookExisting != null && bookExisting.equals(newBook))) {
            return;
        }

        if (stopScanner) {
            L.d(TAG, "addNewBook(); stopScanner requested");
            return;
        }

        baseApplication.addBook(newBook);
    }

    /**
     * Adds files recursively. First takes all files and adds them sorted to the return list. Then
     * sorts the folders, and then adds their content sorted to the return list.
     *
     * @param dir The dirs and files to be added
     * @return All the files containing in a natural sorted order.
     */
    private ArrayList<File> addFilesRecursive(ArrayList<File> dir) {
        ArrayList<File> returnList = new ArrayList<>();
        ArrayList<File> fileList = new ArrayList<>();
        ArrayList<File> dirList = new ArrayList<>();
        for (File f : dir) {
            if (f.exists() && f.isFile()) {
                fileList.add(f);
            } else if (f.exists() && f.isDirectory()) {
                dirList.add(f);
            }
        }
        Collections.sort(fileList, new NaturalOrderComparator());
        returnList.addAll(fileList);
        Collections.sort(dirList, new NaturalOrderComparator());
        for (File f : dirList) {
            ArrayList<File> content = new ArrayList<>();
            File[] containing = f.listFiles();
            if (containing != null) {
                content = new ArrayList<>(Arrays.asList(containing));
            }
            if (content.size() > 0) {
                ArrayList<File> tempReturn = addFilesRecursive(content);
                returnList.addAll(tempReturn);
            }
        }
        return returnList;
    }

    @Nullable
    private Book rootFileToBook(File rootFile) {
        if (stopScanner) {
            return null;
        }

        ArrayList<File> rootFiles = new ArrayList<>();
        rootFiles.add(rootFile);
        rootFiles = addFilesRecursive(rootFiles);
        ArrayList<Chapter> containingMedia = new ArrayList<>();
        ArrayList<File> coverFiles = new ArrayList<>();
        ArrayList<File> musicFiles = new ArrayList<>();
        for (File f : rootFiles) {
            if (isAudio(f)) {
                musicFiles.add(f);
            } else if (isImage(f)) {
                coverFiles.add(f);
            }
        }

        if (musicFiles.size() == 0) {
            L.d(TAG, "assAsBook with file=" + rootFiles + " aborted because it contains no audio files");
        }

        Bitmap cover = null;

        // if there are images, get the first one.
        int dimen = ImageHelper.getSmallerScreenSize(this);
        for (File f : coverFiles) {
            if (cover == null) {
                try {
                    cover = Picasso.with(this).load(f).resize(dimen, dimen).get();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        String bookRoot = rootFile.isDirectory() ?
                rootFile.getAbsolutePath() :
                rootFile.getParent();
        String bookName = rootFile.isDirectory() ?
                rootFile.getName() :
                rootFile.getName().substring(0, rootFile.getName().lastIndexOf("."));

        // get duration and if there is no cover yet, try to get an embedded dover (up to 5 times)
        final int MAX_TRIES_FOR_EMBEDDED_COVER = 5;
        MediaPlayer mp = new MediaPlayer();
        try {
            for (int i = 0; i < musicFiles.size(); i++) {
                File f = musicFiles.get(i);
                int duration = 0;
                try {
                    mp.setDataSource(f.getPath());
                    mp.prepare();
                    duration = mp.getDuration();
                } catch (IOException e) {
                    L.e(TAG, "io error at file f=" + f);
                }
                mp.reset();

                String chapterName = f.getName().substring(0, f.getName().lastIndexOf("."));
                if (duration > 0) {
                    containingMedia.add(new Chapter(f.getAbsolutePath().substring(bookRoot.length() + 1), chapterName, duration));
                }

                if (i < MAX_TRIES_FOR_EMBEDDED_COVER && cover == null) {
                    cover = ImageHelper.getEmbeddedCover(f, this);
                }
                if (stopScanner) {
                    L.d(TAG, "rootFileToBook, stopScanner called");
                    return null;
                }
            }
        } finally {
            mp.release();
        }

        if (containingMedia.size() == 0) {
            L.e(TAG, "Book with root=" + rootFiles + " contains no media");
            return null;
        }

        if (cover != null && !Book.getCoverFile(bookRoot, containingMedia).exists()) {
            ImageHelper.saveCover(cover, this, bookRoot, containingMedia);
        }

        return new Book(bookRoot, bookName, containingMedia, new ArrayList<Bookmark>(), 1.0f, Book.ID_UNKNOWN, Book.ID_UNKNOWN, 0, containingMedia.get(0).getPath(), false);
    }

    @Nullable
    private Book getBookByRoot(File rootFile) {
        if (rootFile.isDirectory()) {
            for (Book b : baseApplication.getAllBooks()) {
                if (rootFile.getAbsolutePath().equals(b.getRoot())) {
                    return b;
                }
            }
        } else if (rootFile.isFile()) {
            for (Book b : baseApplication.getAllBooks()) {
                if (rootFile.getParentFile().getAbsolutePath().equals(b.getRoot())) {
                    Chapter singleChapter = b.getChapters().get(0);
                    if ((b.getRoot() + "/" + singleChapter.getPath()).equals(rootFile.getAbsolutePath())) {
                        return b;
                    }
                }
            }
        }
        return null;
    }
}