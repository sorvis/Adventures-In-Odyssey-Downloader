using System;
using System.Collections.Generic;
using System.IO;
using System.Net;

namespace Odyssey_Downloader
{
    internal class ProcessFile
    {
        protected string fullPath;
        protected string indexFileName;

        public ProcessFile(Config settings)
        {
            fullPath = settings.FullPathToFiles;
            indexFileName = settings.IndexFileName;
        }

        public bool Download(GetFileInfo file)
        {
            if (checkFileList(file.GetFullTitle()))
            {
                Console.WriteLine("Skipping download already have file.");
                return false;
            }
            if(string.IsNullOrEmpty(file.GetFileUrl()) 
                || file.GetFullTitle().Contains("Free Online Christian Ministry Radio Broadcasts"))
            {
                return false;
            }
            else
            {
                string filePath = fullPath + file.FileName;
                getFile(file.GetFileUrl(), filePath);
                writeToIndex(file.GetFullTitle());
                return true;
            }
        }

        private void writeToIndex(string title)
        {
            List<string> fileLines = new List<string>();

            int counter = 0;
            string line;
            // Read the file and display it line by line.
            System.IO.StreamReader file = new System.IO.StreamReader(fullPath + indexFileName);
            while ((line = file.ReadLine()) != null)
            {
                fileLines.Add(line);
                counter++;
            }
            file.Close();

            // add new title then sort
            fileLines.Add(title);
            fileLines.Sort();

            // write array back to file
            // create a writer and open the file
            TextWriter newFile = new StreamWriter(fullPath + indexFileName);
            foreach (string Currentline in fileLines)
            {
                newFile.WriteLine(Currentline);
            }
            // close the stream
            newFile.Close();
        }

        private bool checkFileList(string title)
        {
            bool foundInIndex = false;

            int counter = 0;
            string line;

            // Read the file and display it line by line.
            System.IO.StreamReader file = new System.IO.StreamReader(fullPath + indexFileName);
            while ((line = file.ReadLine()) != null)
            {
                if (title == line)
                {
                    foundInIndex = true;
                }
                counter++;
            }
            file.Close();

            return foundInIndex;
        }

        private void getFile(string url, string fileName)
        {
            WebClient Client = new WebClient();
            Client.DownloadFile(url, fileName);
            Client.Dispose();
        }
    }
}
