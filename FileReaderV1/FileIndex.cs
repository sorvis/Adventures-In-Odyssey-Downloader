using System;
using System.Collections.Generic;
using System.IO;

namespace Odyssey_Downloader
{
    internal class FileIndex : IIndexReader
    {
        protected string fullPath;
        protected string indexFileName;
        protected string fileExtension;

        private void checkForIndexFile(Config settings)
        {
            string path = fullPath + indexFileName;
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

        public void RebuildIndex(Config settings)
        {
            fullPath = settings.FullPathToFiles;
            indexFileName = settings.IndexFileName;
            fileExtension = settings.FileExtension;

            List<string> titleList = new List<string>();
            foreach (string item in getFileNamesInDir(fullPath, fileExtension))
            {
                titleList.Add("Episode " + findElement(item, "#-", "/") + ": " +
                    (findElement(item, fileExtension, "#-")).Replace("_", " "));
            }

            writeListToFile(titleList);
        }

        private string findElement(string Source, string elementEnd, string elementStart)
        {
            int extensionIndex = Source.IndexOf(elementEnd);
            Source = Source.Substring(0, extensionIndex); //cut off source after file
            Source = ReverseString(Source); // reverse the source so that the file url is first
            int httpIndex = Source.IndexOf(ReverseString(elementStart));
            Source = Source.Substring(0, httpIndex); //cut off source before file
            Source = ReverseString(Source); // reverse the URL back to normal

            return Source;
        }

        private static string ReverseString(string text)
        {
            char[] array = text.ToCharArray();
            Array.Reverse(array);
            return new string(array);
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
            TextWriter newFile = new StreamWriter(fullPath + indexFileName);
            foreach (string Currentline in fileLines)
            {
                newFile.WriteLine(Currentline);
            }
            // close the stream
            newFile.Close();
        }

        public bool IndexDetected()
        {
            throw new NotImplementedException();
        }
    }
}