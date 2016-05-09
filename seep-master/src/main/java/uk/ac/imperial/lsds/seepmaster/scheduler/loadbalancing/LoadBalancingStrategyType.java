package uk.ac.imperial.lsds.seepmaster.scheduler.loadbalancing;


public enum LoadBalancingStrategyType {

	DATAPARALLELWITHINPUTDATALOCALITY(0, new DataParallelWithInputDataLocalityLoadBalancingStrategy()),
	LOCALITYSENSITIVE(1, new LocalitySensitiveLoadBalancingStrategy());
	
	private int type;
	private LoadBalancingStrategy strategy;
	
	LoadBalancingStrategyType(int type, LoadBalancingStrategy strategy){
		this.type = type;
		this.strategy = strategy;
	}
	
	public int ofType(){
		return type;
	}
	
	public static LoadBalancingStrategy clazz(int type){
		for(LoadBalancingStrategyType sst : LoadBalancingStrategyType.values()){
			if(sst.ofType() == type){
				return sst.strategy;
			}
		}
		return null;
	}
	
}
