using OdysseyDownloader.FileReader;
using System;

namespace Odyssey_Downloader
{
    internal class Program
    {
        private static void Main(string[] args)
        {
            // TODO perhaps we could switch over to commandline parser
            // get any arguments passed in at the commandline
            Arguments CommandLine = new Arguments(args);

            // load config file
            Config settings = new Config();

            if (CommandLine["config"] != null)
            {
                settings.SetConfigPath(Convert.ToString(CommandLine["config"]));
            }
            else
            {
                settings.SetConfigPath("config.xml");
            }

            // create or update index file
            var fileReaderFactory = new FileReaderFactory(settings);
            IIndexReader indexReader = fileReaderFactory.Get();

            // create file downloader
            ProcessFile downloadAndSave = new ProcessFile(settings, indexReader, new Downloader());
            var multiDownloader = new MultiDownload(settings, downloadAndSave);

            //***********************************************************

            if (CommandLine["rebuild-index"] != null || CommandLine["build-index"] != null)
            {
                indexReader.RebuildIndex();
            }

            if (CommandLine["defaults"] != null)
            {
                settings.setDefaults();
            }

            if (CommandLine["write-defaults"] != null)
            {
                settings.createDefaultConfig();
            }

            if (CommandLine["help"] != null || CommandLine["?"] != null)
            {
                showHelp();
            }
            else if (CommandLine["c"] != null)
            {
                if (CommandLine["c"] == "1")
                {
                    //download just today
                    GetFileInfo todaysFile = new GetFileInfo(settings, 0);
                    downloadAndSave.Download(todaysFile);
                }
                else if (Convert.ToInt16(CommandLine["c"]) > 1)
                {
                    multiDownloader.Run(Convert.ToInt16(CommandLine["c"]));
                }
                else
                {
                    showHelp();
                    Console.WriteLine("\nYou must enter enter a valid number for count 'c'.");
                }
            }
            else if (CommandLine["all"] != null)
            {
                // 35 does whole three weeks
                multiDownloader.Run(35);
            }
            else
            {
                // normal mode
                multiDownloader.Run(settings.NormalMode);
            }
        }

        private static void showHelp()
        {
            Console.WriteLine(" -?               This Help.");
            Console.WriteLine(" -c               Number of files to download.");
            Console.WriteLine(" --all            Retrieve last three weeks.");
            Console.WriteLine(" --defaults       Run using program defaults.");
            Console.WriteLine(" --write-defaults Over write config file with default settings.");
            Console.WriteLine(" --rebuild-index  Rebuild file index.");
            Console.WriteLine(" --config	  Set path to XML config file.");
        }
    }
}