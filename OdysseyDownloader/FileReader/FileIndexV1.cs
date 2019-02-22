using NLog;
using Odyssey_Downloader;
using Odyssey_Downloader.Model;
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;

namespace OdysseyDownloader.FileReader
{
    public class FileIndexV1 : IIndexReader
    {
        private readonly Logger _logger = NLog.LogManager.GetCurrentClassLogger();
        private string _fullPath;
        private string _fileExtension;
        private string _fullPathToIndex;

        public FileIndexV1(Config settings)
        {
            _fullPath = settings.FullPathToFiles;
            _fileExtension = settings.FileExtension;
            _fullPathToIndex = settings.GetIndexFilePath();
        }

        public IEnumerable<AudioFile> RebuildIndex(Config settings)
        {
            List<AudioFile> audioFiles = new List<AudioFile>();

            List<string> titleList = new List<string>();
            foreach (string item in getFileNamesInDir())
            {
                var justFileName = Path.GetFileName(item);
                var title = findElement(justFileName, _fileExtension, "#-").Replace("_", " ");
                var episodeNumber = findElement(justFileName, "#-", "/");
                titleList.Add($"Episode {episodeNumber}: {title}");
                audioFiles.Add(new AudioFile
                {
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
            if (!File.Exists(_fullPathToIndex)) return false;
            try
            {
                var files = ReadIndex();
                return files.Any();
            }
            catch(Exception exception)
            {
                _logger.Error(exception, 
                    $"Unable to read contents of index file at: {_fullPathToIndex}. Probably this is the an older index reader checking for compatibility.");
                return false;
            }
        }

        public IEnumerable<AudioFile> ReadIndex()
        {
            // format of lines look like this: "Episode {episodeNumber}: {title}"
            var lines = File.ReadAllLines(_fullPathToIndex);
            foreach(var line in lines)
            {
                var stripEpisode = line.Replace("Episode ", string.Empty);
                var splitOnColon = stripEpisode.Split(':', 2);
                var episodeNumber = splitOnColon.First();
                var title = splitOnColon.Skip(1).First().Trim();
                yield return new AudioFile {
                    Number = episodeNumber,
                    Title = title,
                    FileName = $"{episodeNumber}#-{title.Replace(' ', '_')}{_fileExtension}",
                };
            }
        }

        public void WriteToIndex(AudioFile fileInfo)
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
            try
            {
                System.IO.StreamReader file = new System.IO.StreamReader(_fullPathToIndex);
                file.Close();
            }
            catch
            {
                File.Create(_fullPathToIndex).Dispose();
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
            if (httpIndex > 0)
            {
                Source = Source.Substring(0, httpIndex); //cut off source before file
            }

            Source = reverseString(Source); // reverse the URL back to normal

            return Source;
        }

        private List<string> getFileNamesInDir()
        {
            var dir = _fullPath;
            var extension = _fileExtension;
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
            TextWriter newFile = new StreamWriter(_fullPathToIndex);
            foreach (string Currentline in fileLines)
            {
                newFile.WriteLine(Currentline);
            }
            // close the stream
            newFile.Close();
        }
    }
}