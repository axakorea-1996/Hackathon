public class TestService {
    public String name;  // public 필드 (AI가 잡아야 함)
    
    public void save(String data) {
        // 트랜잭션 없음 (AI가 잡아야 함)
        System.out.println(data);
    }
}
