async function getPing() {
  const res = await fetch("http://localhost:8080/ping", {
    cache: "no-store",
  });

  return res.text();
}

export default async function Home() {
  const result = await getPing();

  return (
    <div className="p-6">
      <h1 className="text-xl font-semibold">后端回应：{result}</h1>
    </div>
  );
}
