using System;


namespace Odyssey_Downloader
{
    class multiDownload
    {
        public multiDownload(config settings, ref processFile downloadAndSave, int limit)
        {
            int counter = 0;
            bool runLoop = true;
            while (runLoop)
            {
                getFileInfo currentFile = new getFileInfo(settings, counter);
                if (currentFile.getFileUrl() != "" && counter < limit)
                {
                    Console.WriteLine("Downloading: "+currentFile.getFullTitle());
                    downloadAndSave.download(currentFile);
                }
                else
                {
                    runLoop = false;
                }
                counter++;
            }
        }
    }
}
