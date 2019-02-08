using Odyssey_Downloader;
using Odyssey_Downloader.Model;
using System;
using System.Collections.Generic;
using System.IO;

namespace OdysseyDownloader.FileReaderV1
{
    public class FileIndex : IIndexReader
    {
        private string _fullPath;
        private string _indexFileName;
        private string _fileExtension;

        public IEnumerable<AudioFile> RebuildIndex(Config settings)
        {
            _fullPath = settings.FullPathToFiles;
            _indexFileName = settings.IndexFileName;
            _fileExtension = settings.FileExtension;

            List<AudioFile> audioFiles = new List<AudioFile>();

            List<string> titleList = new List<string>();
            var filesInDirectory = getFileNamesInDir(_fullPath, _fileExtension);
            foreach (string item in filesInDirectory)
            {
                var justFileName = Path.GetFileName(item);
                var title = findElement(justFileName, _fileExtension, "#-").Replace("_", " ");
                var episodeNumber = findElement(justFileName, "#-", "/");
                titleList.Add($"Episode {episodeNumber}: {title}");
                audioFiles.Add(new AudioFile {
                    Title = title,
                    FileName = justFileName,
                    Number = episodeNumber,
                });
            }

            writeListToFile(titleList);
            return audioFiles;
        }

        public bool IndexDetected()
        {
            throw new NotImplementedException();
        }

        private static string reverseString(string text)
        {
            char[] array = text.ToCharArray();
            Array.Reverse(array);
            return new string(array);
        }

        private void checkForIndexFile(Config settings)
        {
            string path = _fullPath + _indexFileName;
            try
            {
                System.IO.StreamReader file = new System.IO.StreamReader(path);
                file.Close();
            }
            catch
            {
                File.Create(path).Dispose();
                Console.WriteLine("Found no index file so rebuilding it.");
                RebuildIndex(settings);
            }
        }
        private string findElement(string Source, string elementEnd, string elementStart)
        {
            int extensionIndex = Source.IndexOf(elementEnd);
            Source = Source.Substring(0, extensionIndex); //cut off source after file
            Source = reverseString(Source); // reverse the source so that the file url is first
            int httpIndex = Source.IndexOf(reverseString(elementStart));
            if(httpIndex > 0) Source = Source.Substring(0, httpIndex); //cut off source before file
            Source = reverseString(Source); // reverse the URL back to normal

            return Source;
        }
        private List<string> getFileNamesInDir(string dir, string extension)
        {
            List<string> list = new List<string>();

            // Process the list of files found in the directory.
            foreach (string fileName in Directory.GetFiles(dir))
            {
                // do something with fileName
                if (fileName.Contains(extension))
                {
                    list.Add(fileName);
                }
            }

            list.Sort();

            return list;
        }

        private void writeListToFile(List<string> fileLines)
        {
            TextWriter newFile = new StreamWriter(_fullPath + _indexFileName);
            foreach (string Currentline in fileLines)
            {
                newFile.WriteLine(Currentline);
            }
            // close the stream
            newFile.Close();
        }
    }
}