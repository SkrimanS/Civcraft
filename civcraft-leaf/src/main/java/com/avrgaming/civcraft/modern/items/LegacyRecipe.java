package com.avrgaming.civcraft.modern.items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure matching: metadata identities, sparse shaped recipes, wildcard vanilla variants. */
public record LegacyRecipe(String id, List<String> shape, Map<Character,Ingredient> symbols,
                           List<Ingredient> shapeless) {
    public record Token(String customId, String material) { }
    public record Ingredient(String customId, Set<String> materials) {
        public boolean matches(Token token) {
            if (token==null)return false;
            if(customId!=null)return customId.equals(token.customId());
            return token.customId()==null && materials.contains(token.material());
        }
    }
    public boolean matches(List<Token> matrix, int width) {
        if(width<1 || matrix.size()%width!=0)return false;
        if(shape.isEmpty())return matchesShapeless(matrix);
        int height=matrix.size()/width;
        int recipeWidth=shape.stream().mapToInt(String::length).max().orElse(0);
        if(recipeWidth>width || shape.size()>height)return false;
        for(int y=0;y<=height-shape.size();y++)for(int x=0;x<=width-recipeWidth;x++)
            if(matchesShape(matrix,width,x,y,recipeWidth,false) || matchesShape(matrix,width,x,y,recipeWidth,true))return true;
        return false;
    }
    private boolean matchesShape(List<Token> matrix,int width,int ox,int oy,int recipeWidth,boolean mirror) {
        for(int i=0;i<matrix.size();i++) {
            int x=i%width-ox,y=i/width-oy;
            Ingredient wanted=null;
            if(y>=0 && y<shape.size() && x>=0 && x<recipeWidth) {
                String row=shape.get(y); int at=mirror?recipeWidth-1-x:x;
                char symbol=at<row.length()?row.charAt(at):' ';
                if(symbol!=' ')wanted=symbols.get(symbol);
                if(symbol!=' ' && wanted==null)return false;
            }
            Token actual=matrix.get(i);
            if(wanted==null ? actual!=null : !wanted.matches(actual))return false;
        }
        return true;
    }
    private boolean matchesShapeless(List<Token> matrix) {
        List<Token> actual=new ArrayList<>();
        for(Token token:matrix)if(token!=null)actual.add(token);
        if(actual.size()!=shapeless.size())return false;
        return assign(actual,0,new boolean[shapeless.size()]);
    }
    private boolean assign(List<Token> tokens,int index,boolean[] used) {
        if(index==tokens.size())return true;
        for(int i=0;i<shapeless.size();i++)if(!used[i] && shapeless.get(i).matches(tokens.get(index))) {
            used[i]=true;if(assign(tokens,index+1,used))return true;used[i]=false;
        }
        return false;
    }
}
